const { GoogleGenerativeAI, HarmCategory, HarmBlockThreshold } = require("@google/generative-ai");
const BaseProvider = require('./base');
const config = require('../../config');

/**
 * Провайдер для Gemma-3-27b (высокие лимиты, для некритичных задач)
 * Использует те же ключи что и Gemini, но другую модель
 */
class GemmaProvider extends BaseProvider {
    constructor(keys) {
        super('Gemma', keys);
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
            maxOutputTokens: 4000,
            temperature: 0.9,
        };

        this.model = genAI.getGenerativeModel({
            model: 'gemma-3-27b-it', // Модель Gemma с высокими лимитами
            safetySettings: safetySettings,
            generationConfig: generationConfig
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
        return false; // Gemma не поддерживает vision
    }

    supportsSearch() {
        return false; // Gemma не поддерживает поиск
    }

    async generate(prompt, options = {}) {
        if (!this.isAvailable()) {
            throw new Error('Gemma: Провайдер недоступен (нет ключей)');
        }

        const apiCall = async () => {
            // Gemma не поддерживает медиа
            if (options.mediaBuffer) {
                throw new Error('Gemma: Медиа не поддерживается');
            }

            // Генерация
            const result = await this.model.generateContent({
                contents: [{ role: 'user', parts: [{ text: prompt }] }],
                generationConfig: {
                    maxOutputTokens: options.maxTokens || 2000,
                    temperature: options.temperature || 0.9
                }
            });

            const response = result.response;
            let text = response.text();

            // Очистка технического мусора
            text = text.replace(/^toolcode[\s\S]*?print\(.*?\)\s*/i, '');
            text = text.replace(/^thought[\s\S]*?\n\n/i, '');
            text = text.replace(/```json/g, '').replace(/```/g, '').trim();

            // Проверка на пустой ответ
            if (!text || text.trim().length === 0) {
                throw new Error('Gemma: Ответ пустой');
            }

            // Очистка JSON обёрток для expectJson
            if (options.expectJson) {
                const firstBrace = text.indexOf('{');
                const lastBrace = text.lastIndexOf('}');
                if (firstBrace !== -1 && lastBrace !== -1) {
                    text = text.substring(firstBrace, lastBrace + 1);
                }
            }

            return text;
        };

        return await this.executeWithRetry(apiCall);
    }

    /**
     * Gemma поддерживает system message через добавление в промпт
     */
    async generateWithSystem(systemPrompt, userPrompt, options = {}) {
        const combinedPrompt = `${systemPrompt}\n\n${userPrompt}`;
        return await this.generate(combinedPrompt, options);
    }
}

module.exports = GemmaProvider;
