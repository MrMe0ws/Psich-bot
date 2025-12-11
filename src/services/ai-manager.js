const GeminiProvider = require('./providers/gemini');
const GroqProvider = require('./providers/groq');
const DeepSeekProvider = require('./providers/deepseek');
const GemmaProvider = require('./providers/gemma');
const prompts = require('../core/prompts');

/**
 * Менеджер AI провайдеров с автоматической ротацией
 */
class AIManager {
    constructor(config) {
        this.providers = [];
        this.currentProviderIndex = 0;

        // Инициализируем провайдеры из конфига
        if (config.geminiKeys && config.geminiKeys.length > 0) {
            const gemini = new GeminiProvider(config.geminiKeys);
            gemini.setSystemInstruction(prompts.system());
            this.providers.push(gemini);
            console.log(`[AI Manager] Gemini инициализирован с ${config.geminiKeys.length} ключами`);
        }

        if (config.groqKeys && config.groqKeys.length > 0) {
            const groq = new GroqProvider(config.groqKeys);
            this.providers.push(groq);
            console.log(`[AI Manager] Groq инициализирован с ${config.groqKeys.length} ключами`);
        }

        if (config.deepseekKeys && config.deepseekKeys.length > 0) {
            const deepseek = new DeepSeekProvider(config.deepseekKeys);
            this.providers.push(deepseek);
            console.log(`[AI Manager] DeepSeek инициализирован с ${config.deepseekKeys.length} ключами`);
        }

        // Gemma использует те же ключи что и Gemini, но для некритичных задач и общения
        if (config.geminiKeys && config.geminiKeys.length > 0) {
            const gemma = new GemmaProvider(config.geminiKeys);
            this.gemmaProvider = gemma; // Сохраняем отдельно для некритичных задач
            this.providers.push(gemma); // Добавляем в список для общения
            console.log(`[AI Manager] Gemma инициализирован с ${config.geminiKeys.length} ключами (для некритичных задач и общения)`);
        }

        if (this.providers.length === 0) {
            console.error('[AI Manager] КРИТИЧЕСКАЯ ОШИБКА: Нет доступных AI провайдеров!');
        } else {
            console.log(`[AI Manager] Готов к работе. Провайдеров: ${this.providers.length}`);
        }
    }

    /**
     * Безопасный парсинг JSON
     */
    safeJsonParse(text, fallback = null) {
        if (!text) return fallback;
        try {
            return JSON.parse(text);
        } catch (e) {
            console.warn(`[AI Manager] JSON Parse Error: ${e.message}. Text snippet: ${text.substring(0, 50)}...`);
            return fallback;
        }
    }

    /**
     * Получить текущее время (Екатеринбург)
     */
    getCurrentTime() {
        return new Date().toLocaleString("ru-RU", {
            timeZone: "Asia/Yekaterinburg",
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    /**
     * Выбрать оптимальный провайдер для задачи
     * Gemini используется ТОЛЬКО для vision/search, для обычного общения - другие модели
     */
    selectProvider(requiresVision = false, requiresSearch = false) {
        // Если нужен vision или search - используем только Gemini
        if (requiresVision || requiresSearch) {
            for (const provider of this.providers) {
                if (!provider.isAvailable()) continue;
                if (provider.name === 'Gemini') {
                    if (requiresVision && !provider.supportsVision()) continue;
                    if (requiresSearch && !provider.supportsSearch()) continue;
                    return provider;
                }
            }
        }

        // Для обычного общения - приоритет: Groq > DeepSeek > Gemma > Gemini (последний резерв)
        const priorityOrder = ['Groq', 'DeepSeek', 'Gemma', 'Gemini'];

        for (const priorityName of priorityOrder) {
            for (const provider of this.providers) {
                if (provider.name === priorityName && provider.isAvailable()) {
                    return provider;
                }
            }
        }

        // Если ничего не нашли, берем любой доступный
        return this.providers.find(p => p.isAvailable());
    }

    /**
     * Попытка выполнить с фоллбэком на других провайдеров
     */
    async executeWithFallback(taskFn, requiresVision = false, requiresSearch = false) {
        const preferredProvider = this.selectProvider(requiresVision, requiresSearch);

        if (!preferredProvider) {
            throw new Error('Нет доступных AI провайдеров');
        }

        // Сначала пробуем предпочтительный
        try {
            return await taskFn(preferredProvider);
        } catch (error) {
            const errorMsg = error.message || String(error);
            const isQuotaExhausted = errorMsg.includes('исчерпал') ||
                errorMsg.includes('quota') ||
                errorMsg.includes('429') ||
                errorMsg.includes('limit') ||
                errorMsg.includes('Все ключи') ||
                errorMsg.includes('Insufficient Balance') ||
                errorMsg.includes('402') ||
                errorMsg.includes('insufficient');

            if (isQuotaExhausted) {
                console.log(`[AI Manager] ${preferredProvider.name} исчерпал лимит: ${errorMsg.substring(0, 100)}`);
            } else {
                console.log(`[AI Manager] ${preferredProvider.name} упал: ${errorMsg.substring(0, 100)}`);
            }

            // Пробуем других провайдеров
            const availableProviders = this.providers.filter(p => p.isAvailable() && p !== preferredProvider);
            console.log(`[AI Manager] Доступно провайдеров для fallback: ${availableProviders.length} из ${this.providers.length}`);
            console.log(`[AI Manager] Список провайдеров: ${this.providers.map(p => `${p.name}(${p.isAvailable() ? 'доступен' : 'недоступен'})`).join(', ')}`);

            for (const provider of this.providers) {
                if (provider === preferredProvider) {
                    continue;
                }
                if (!provider.isAvailable()) {
                    console.log(`[AI Manager] Пропускаем ${provider.name} (недоступен)`);
                    continue;
                }

                // Пропускаем если не подходит по фичам
                if (requiresVision && !provider.supportsVision()) {
                    console.log(`[AI Manager] Пропускаем ${provider.name} (не поддерживает vision)`);
                    continue;
                }

                try {
                    console.log(`[AI Manager] Переключаюсь на ${provider.name}...`);
                    return await taskFn(provider);
                } catch (fallbackError) {
                    const fallbackMsg = fallbackError.message || String(fallbackError);
                    console.log(`[AI Manager] ${provider.name} тоже упал: ${fallbackMsg.substring(0, 100)}`);
                    continue;
                }
            }

            // Все провайдеры недоступны
            throw new Error('Все AI провайдеры исчерпали лимиты или недоступны');
        }
    }

    /**
     * Основной метод генерации ответа
     */
    async getResponse(history, currentMessage, imageBuffer = null, mimeType = "image/jpeg", userInstruction = "", userProfile = null, isSpontaneous = false) {
        const requiresVision = !!imageBuffer;
        const requiresSearch = false; // Можно сделать автоопределение по ключевым словам

        return await this.executeWithFallback(async (provider) => {
            const relevantHistory = history.slice(-20);
            const contextStr = relevantHistory.map(m => `${m.role}: ${m.text}`).join('\n');

            let personalInfo = "";
            let replyContext = "";

            if (currentMessage.replyText) {
                replyContext = `!!! ПОЛЬЗОВАТЕЛЬ ОТВЕТИЛ НА СООБЩЕНИЕ:\n"${currentMessage.replyText}"`;
            }

            if (userInstruction) personalInfo += `\n!!! СПЕЦ-ИНСТРУКЦИЯ !!!\n${userInstruction}\n`;

            if (userProfile) {
                const score = userProfile.relationship || 50;
                let relationText = "";
                if (score <= 20) relationText = `СТАТУС: ВРАГ (${score}/100). Отвечай грубо, с сарказмом.`;
                else if (score <= 40) relationText = `СТАТУС: ХОЛОД (${score}/100). Язви, не доверяй.`;
                else if (score >= 80) relationText = `СТАТУС: БРАТАН (${score}/100). Поддерживай, шути по-доброму.`;

                personalInfo += `\n--- ДОСЬЕ ---\nФакты: ${userProfile.facts || "Нет"}\n${relationText}\n-----------------\n`;
            }

            const systemPrompt = prompts.system();
            const fullPromptText = prompts.mainChat({
                time: this.getCurrentTime(),
                isSpontaneous: isSpontaneous,
                userMessage: currentMessage.text,
                replyContext: replyContext,
                history: contextStr,
                personalInfo: personalInfo,
                senderName: currentMessage.sender
            });

            // Для Groq, DeepSeek и Gemma без медиа используем generateWithSystem
            if ((provider.name === 'Groq' || provider.name === 'DeepSeek' || provider.name === 'Gemma') && provider.generateWithSystem && !imageBuffer) {
                return await provider.generateWithSystem(systemPrompt, fullPromptText, {
                    maxTokens: 2500,
                    temperature: 0.9
                });
            }

            // Для Gemma без generateWithSystem - добавляем системный промпт в начало
            if (provider.name === 'Gemma' && !imageBuffer) {
                const finalPrompt = `${systemPrompt}\n\n${fullPromptText}`;
                return await provider.generate(finalPrompt, {
                    maxTokens: 2500,
                    temperature: 0.9
                });
            }

            // Для Groq с медиа или Gemini - добавляем системную инструкцию в начало промпта
            const finalPrompt = provider.name === 'Groq' && imageBuffer
                ? `${systemPrompt}\n\n${fullPromptText}`
                : fullPromptText;

            return await provider.generate(finalPrompt, {
                systemPrompt: systemPrompt, // Передаем системный промпт для DeepSeek
                mediaBuffer: imageBuffer,
                mimeType: mimeType,
                maxTokens: 2500,
                temperature: 0.9
            });
        }, requiresVision, requiresSearch);
    }

    /**
     * Определить реакцию (эмодзи) - используем Gemma для экономии лимитов
     */
    async determineReaction(contextText) {
        const allowed = ["👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌", "🕊", "🤡", "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨", "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿", "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷‍♂", "🤷", "🤷‍♀", "😡"];

        try {
            // Сначала пробуем Gemma (высокие лимиты)
            if (this.gemmaProvider && this.gemmaProvider.isAvailable()) {
                try {
                    const promptText = prompts.reaction(contextText, allowed.join(" "));
                    const text = await this.gemmaProvider.generate(promptText, { maxTokens: 50 });
                    const match = text.match(/(\p{Emoji_Presentation}|\p{Extended_Pictographic})/u);
                    if (match && allowed.includes(match[0])) return match[0];
                } catch (e) {
                    console.log(`[AI Manager] Gemma не смогла определить реакцию, пробуем fallback: ${e.message.substring(0, 50)}`);
                }
            }

            // Fallback на другие провайдеры
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.reaction(contextText, allowed.join(" "));
                const text = await provider.generate(promptText, { maxTokens: 50 });

                const match = text.match(/(\p{Emoji_Presentation}|\p{Extended_Pictographic})/u);
                if (match && allowed.includes(match[0])) return match[0];
                return null;
            });
        } catch (e) {
            return null;
        }
    }

    /**
     * Анализ пользователя (быстрый) - используем Gemma для экономии лимитов
     */
    async analyzeUserImmediate(lastMessages, currentProfile) {
        try {
            // Сначала пробуем Gemma (высокие лимиты)
            if (this.gemmaProvider && this.gemmaProvider.isAvailable()) {
                try {
                    const promptText = prompts.analyzeImmediate(currentProfile, lastMessages);
                    let text = await this.gemmaProvider.generate(promptText, { expectJson: true, maxTokens: 1000 });

                    // Очистка JSON
                    text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                    const firstBrace = text.indexOf('{');
                    const lastBrace = text.lastIndexOf('}');
                    if (firstBrace !== -1 && lastBrace !== -1) {
                        text = text.substring(firstBrace, lastBrace + 1);
                    }

                    const result = this.safeJsonParse(text, {});
                    if (result && Object.keys(result).length > 0) {
                        return result;
                    }
                } catch (e) {
                    console.log(`[AI Manager] Gemma не смогла проанализировать, пробуем fallback: ${e.message.substring(0, 50)}`);
                }
            }

            // Fallback на другие провайдеры
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.analyzeImmediate(currentProfile, lastMessages);
                let text = await provider.generate(promptText, { expectJson: true, maxTokens: 1000 });

                // Очистка JSON
                text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                const firstBrace = text.indexOf('{');
                const lastBrace = text.lastIndexOf('}');
                if (firstBrace !== -1 && lastBrace !== -1) {
                    text = text.substring(firstBrace, lastBrace + 1);
                }

                return this.safeJsonParse(text, {});
            });
        } catch (e) {
            console.error(`[AI Manager] Ошибка анализа: ${e.message}`);
            return null;
        }
    }

    /**
     * Массовый анализ (архивация) - используем Gemma для экономии лимитов
     */
    async analyzeBatch(messagesBatch, currentProfiles) {
        try {
            // Сначала пробуем Gemma (высокие лимиты)
            if (this.gemmaProvider && this.gemmaProvider.isAvailable()) {
                try {
                    const chatLog = messagesBatch.map(m => `[ID:${m.userId}] ${m.name}: ${m.text}`).join('\n');
                    const knownInfo = Object.entries(currentProfiles).map(([uid, p]) => `ID:${uid} -> ${p.realName}, ${p.facts}, ${p.attitude}`).join('\n');

                    const promptText = prompts.analyzeBatch(knownInfo, chatLog);
                    let text = await this.gemmaProvider.generate(promptText, { expectJson: true, maxTokens: 2000 });

                    text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                    const firstBrace = text.indexOf('{');
                    const lastBrace = text.lastIndexOf('}');
                    if (firstBrace !== -1 && lastBrace !== -1) {
                        text = text.substring(firstBrace, lastBrace + 1);
                    }

                    const result = this.safeJsonParse(text, {});
                    if (result && Object.keys(result).length > 0) {
                        return result;
                    }
                } catch (e) {
                    console.log(`[AI Manager] Gemma не смогла проанализировать батч, пробуем fallback: ${e.message.substring(0, 50)}`);
                }
            }

            // Fallback на другие провайдеры
            return await this.executeWithFallback(async (provider) => {
                const chatLog = messagesBatch.map(m => `[ID:${m.userId}] ${m.name}: ${m.text}`).join('\n');
                const knownInfo = Object.entries(currentProfiles).map(([uid, p]) => `ID:${uid} -> ${p.realName}, ${p.facts}, ${p.attitude}`).join('\n');

                const promptText = prompts.analyzeBatch(knownInfo, chatLog);
                let text = await provider.generate(promptText, { expectJson: true, maxTokens: 2000 });

                text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                const firstBrace = text.indexOf('{');
                const lastBrace = text.lastIndexOf('}');
                if (firstBrace !== -1 && lastBrace !== -1) {
                    text = text.substring(firstBrace, lastBrace + 1);
                }

                return this.safeJsonParse(text, {});
            });
        } catch (e) {
            return null;
        }
    }

    /**
     * Генерация описания профиля
     */
    async generateProfileDescription(profileData, targetName) {
        try {
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.profileDescription(targetName, profileData);
                return await provider.generate(promptText);
            });
        } catch (e) {
            return "Не знаю такого.";
        }
    }

    /**
     * Генерация фразы для монетки/рандома
     */
    async generateFlavorText(task, result) {
        try {
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.flavor(task, result);
                const text = await provider.generate(promptText, { maxTokens: 100 });
                return text.trim().replace(/^[\"']|[\"']$/g, '');
            });
        } catch (e) {
            return `${result}`;
        }
    }

    /**
     * Решение о вмешательстве в диалог
     */
    async shouldAnswer(lastMessages) {
        try {
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.shouldAnswer(lastMessages);
                const text = await provider.generate(promptText, { maxTokens: 10 });
                return text.toUpperCase().includes('YES');
            });
        } catch (e) {
            return false;
        }
    }

    /**
     * Транскрибация аудио
     */
    async transcribeAudio(audioBuffer, userName = "Пользователь", mimeType = "audio/ogg") {
        try {
            return await this.executeWithFallback(async (provider) => {
                const promptText = prompts.transcription(userName);
                let text = await provider.generate(promptText, {
                    mediaBuffer: audioBuffer,
                    mimeType: mimeType,
                    expectJson: true,
                    maxTokens: 1000
                });

                text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                const firstBrace = text.indexOf('{');
                const lastBrace = text.lastIndexOf('}');
                if (firstBrace !== -1 && lastBrace !== -1) {
                    text = text.substring(firstBrace, lastBrace + 1);
                }

                return this.safeJsonParse(text, null);
            }, true); // Требует vision/audio
        } catch (e) {
            return null;
        }
    }

    /**
     * Парсинг напоминания
     */
    async parseReminder(userText, contextText = "") {
        try {
            return await this.executeWithFallback(async (provider) => {
                const now = this.getCurrentTime();
                const promptText = prompts.parseReminder(now, userText, contextText);

                let text = await provider.generate(promptText, { expectJson: true, maxTokens: 500 });

                text = text.replace(/```json/g, '').replace(/```/g, '').trim();
                const firstBrace = text.indexOf('{');
                const lastBrace = text.lastIndexOf('}');
                if (firstBrace !== -1 && lastBrace !== -1) {
                    text = text.substring(firstBrace, lastBrace + 1);
                }

                return this.safeJsonParse(text, null);
            });
        } catch (e) {
            return null;
        }
    }
}

module.exports = AIManager;

