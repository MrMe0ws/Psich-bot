const { GoogleGenerativeAI, HarmCategory, HarmBlockThreshold } = require("@google/generative-ai");
const BaseProvider = require('./base');
const config = require('../../config');

class GeminiProvider extends BaseProvider {
    constructor(keys) {
        super('Gemini', keys);
        this.model = null;
        if (this.isAvailable()) {
            this.initModel();
        }
    }

    initModel() {
        const currentKey = this.getCurrentKey();
        if (!currentKey) return;

        const genAI = new GoogleGenerativeAI(currentKey);

        const safetySettings = [
            { category: HarmCategory.HARM_CATEGORY_HARASSMENT, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_HATE_SPEECH, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT, threshold: HarmBlockThreshold.BLOCK_NONE },
        ];

        const generationConfig = {
            maxOutputTokens: 8000,
            temperature: 0.9,
        };

        this.model = genAI.getGenerativeModel({
            model: config.modelName,
            safetySettings: safetySettings,
            generationConfig: generationConfig,
            tools: [{ googleSearch: {} }]
        });
    }

    rotateKey() {
        const result = super.rotateKey();
        if (result) {
            this.initModel();
        }
        return result;
    }

    supportsVision() {
        return true;
    }

    supportsSearch() {
        return true;
    }

    async generate(prompt, options = {}) {
        if (!this.isAvailable()) {
            throw new Error('Gemini: Провайдер недоступен (нет ключей)');
        }

        const apiCall = async () => {
            let promptParts = [];

            // Если есть медиа (изображение, видео)
            if (options.mediaBuffer && options.mimeType) {
                promptParts.push({
                    inlineData: {
                        mimeType: options.mimeType,
                        data: options.mediaBuffer.toString("base64")
                    }
                });
                promptParts.push({ text: "Проанализируй этот файл. Опиши, что там, или ответь на вопрос по нему." });
            }

            // Основной текст промпта
            promptParts.push({ text: prompt });

            // Генерация
            const result = await this.model.generateContent({
                contents: [{ role: 'user', parts: promptParts }],
                generationConfig: {
                    maxOutputTokens: options.maxTokens || 2500,
                    temperature: options.temperature || 0.9
                }
            });

            const response = result.response;

            // Проверка на блокировки безопасности
            if (response.candidates && response.candidates.length > 0) {
                const candidate = response.candidates[0];
                if (candidate.finishReason) {
                    // Если ответ заблокирован безопасностью
                    if (candidate.finishReason === 'SAFETY' || candidate.finishReason === 'RECITATION') {
                        const safetyRatings = candidate.safetyRatings || [];
                        const blockedCategories = safetyRatings
                            .filter(r => r.probability === 'HIGH' || r.probability === 'MEDIUM')
                            .map(r => r.category);

                        if (blockedCategories.length > 0) {
                            throw new Error(`Gemini: Ответ заблокирован безопасностью (${blockedCategories.join(', ')})`);
                        }
                    }
                    // Если ответ был отфильтрован по другим причинам
                    if (candidate.finishReason === 'OTHER' || candidate.finishReason === 'MAX_TOKENS') {
                        console.log(`[Gemini] Предупреждение: finishReason = ${candidate.finishReason}`);
                    }
                }
            }

            let text = '';
            try {
                text = response.text();
            } catch (textError) {
                // Если text() выбрасывает ошибку, проверяем кандидатов
                if (response.candidates && response.candidates.length > 0) {
                    const candidate = response.candidates[0];
                    if (candidate.content && candidate.content.parts && candidate.content.parts.length > 0) {
                        text = candidate.content.parts
                            .filter(part => part.text)
                            .map(part => part.text)
                            .join('');
                    }
                }

                // Если всё ещё пусто, выбрасываем ошибку
                if (!text || text.trim().length === 0) {
                    throw new Error(`Gemini: Не удалось получить текст ответа. FinishReason: ${response.candidates?.[0]?.finishReason || 'UNKNOWN'}`);
                }
            }

            // Очистка технического мусора
            text = text.replace(/^toolcode[\s\S]*?print\(.*?\)\s*/i, '');
            text = text.replace(/^thought[\s\S]*?\n\n/i, '');
            text = text.replace(/```json/g, '').replace(/```/g, '').trim();

            // Проверка на пустой ответ после очистки
            if (!text || text.trim().length === 0) {
                const finishReason = response.candidates?.[0]?.finishReason || 'UNKNOWN';
                throw new Error(`Gemini: Ответ пустой после обработки. FinishReason: ${finishReason}`);
            }

            // Добавление источников (если Google Search использовался)
            if (response.candidates && response.candidates[0].groundingMetadata) {
                const metadata = response.candidates[0].groundingMetadata;
                if (metadata.groundingChunks) {
                    const links = [];
                    metadata.groundingChunks.forEach(chunk => {
                        if (chunk.web && chunk.web.uri) {
                            let siteName = "Источник";
                            try { siteName = chunk.web.title || "Источник"; } catch (e) { }
                            links.push(`[${siteName}](${chunk.web.uri})`);
                        }
                    });
                    const uniqueLinks = [...new Set(links)].slice(0, 3);
                    if (uniqueLinks.length > 0) text += "\n\nНашел тут: " + uniqueLinks.join(" • ");
                }
            }

            return text;
        };

        // Выполняем с retry, но также обрабатываем пустые ответы
        // Сохраняем начальный индекс ключа для отслеживания полного цикла
        const initialKeyIndex = this.currentKeyIndex;
        const maxEmptyRetries = Math.min(this.keys.length, 3); // Максимум 3 попытки с разными ключами
        let emptyRetryCount = 0;
        let lastError = null;

        while (emptyRetryCount < maxEmptyRetries) {
            try {
                const result = await this.executeWithRetry(apiCall);

                // Если результат не пустой, возвращаем его
                if (result && result.trim().length > 0) {
                    return result;
                }

                // Если результат пустой, пробуем другой ключ
                console.log(`[Gemini] Получен пустой ответ (попытка ${emptyRetryCount + 1}/${maxEmptyRetries}), пробую другой ключ...`);
                emptyRetryCount++;

                // Проверяем, не вернулись ли мы к начальному ключу (полный цикл)
                if (emptyRetryCount >= maxEmptyRetries ||
                    (this.rotateKey() && this.currentKeyIndex === initialKeyIndex && emptyRetryCount > 1)) {
                    throw new Error('Gemini: Все ключи вернули пустой ответ');
                }

                // Продолжаем с новым ключом
                continue;
            } catch (error) {
                // Если это ошибка пустого ответа, пробуем другой ключ
                if (error.message.includes('пустой') || error.message.includes('пустой ответ') ||
                    error.message.includes('Все ключи вернули пустой ответ')) {
                    lastError = error;
                    emptyRetryCount++;

                    // Проверяем, не вернулись ли мы к начальному ключу
                    if (emptyRetryCount >= maxEmptyRetries ||
                        (this.rotateKey() && this.currentKeyIndex === initialKeyIndex && emptyRetryCount > 1)) {
                        throw new Error('Gemini: Все ключи вернули пустой ответ');
                    }

                    console.log(`[Gemini] Ошибка пустого ответа, пробую другой ключ (попытка ${emptyRetryCount}/${maxEmptyRetries})...`);
                    continue;
                }

                // Если это не ошибка пустого ответа, пробрасываем ошибку
                throw error;
            }
        }

        // Если все попытки исчерпаны
        throw lastError || new Error('Gemini: Все ключи вернули пустой ответ');
    }

    /**
     * Установка системной инструкции
     */
    setSystemInstruction(instruction) {
        if (!this.model) return;

        const currentKey = this.getCurrentKey();
        const genAI = new GoogleGenerativeAI(currentKey);

        const safetySettings = [
            { category: HarmCategory.HARM_CATEGORY_HARASSMENT, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_HATE_SPEECH, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_SEXUALLY_EXPLICIT, threshold: HarmBlockThreshold.BLOCK_NONE },
            { category: HarmCategory.HARM_CATEGORY_DANGEROUS_CONTENT, threshold: HarmBlockThreshold.BLOCK_NONE },
        ];

        const generationConfig = {
            maxOutputTokens: 8000,
            temperature: 0.9,
        };

        this.model = genAI.getGenerativeModel({
            model: config.modelName,
            systemInstruction: instruction,
            safetySettings: safetySettings,
            generationConfig: generationConfig,
            tools: [{ googleSearch: {} }]
        });
    }
}

module.exports = GeminiProvider;

