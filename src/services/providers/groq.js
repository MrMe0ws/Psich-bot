const Groq = require('groq-sdk');
const BaseProvider = require('./base');

class GroqProvider extends BaseProvider {
    constructor(keys) {
        super('Groq', keys);
        this.client = null;
        this.model = 'llama-3.3-70b-versatile'; // Лучшая бесплатная модель для текста
        this.visionModel = 'llama-3.2-90b-vision-preview'; // Для картинок

        if (this.isAvailable()) {
            this.initClient();
        }
    }

    initClient() {
        const currentKey = this.getCurrentKey();
        if (!currentKey) return;

        this.client = new Groq({
            apiKey: currentKey
        });
    }

    rotateKey() {
        const result = super.rotateKey();
        if (result) {
            this.initClient();
        }
        return result;
    }

    supportsVision() {
        return true; // Groq поддерживает vision через Llama 3.2
    }

    supportsSearch() {
        return false; // Groq не поддерживает встроенный поиск
    }

    async generate(prompt, options = {}) {
        if (!this.isAvailable()) {
            throw new Error('Groq: Провайдер недоступен (нет ключей)');
        }

        const apiCall = async () => {
            const messages = [];

            // Если есть медиа
            if (options.mediaBuffer && options.mimeType) {
                if (!options.mimeType.startsWith('image/')) {
                    throw new Error('Groq поддерживает только изображения для vision');
                }

                // Groq Vision требует base64 data URL
                const base64Data = options.mediaBuffer.toString('base64');

                messages.push({
                    role: 'user',
                    content: [
                        {
                            type: 'text',
                            text: prompt
                        },
                        {
                            type: 'image_url',
                            image_url: {
                                url: `data:${options.mimeType};base64,${base64Data}`
                            }
                        }
                    ]
                });

                // Используем vision модель
                const completion = await this.client.chat.completions.create({
                    model: this.visionModel,
                    messages: messages,
                    max_tokens: options.maxTokens || 2048,
                    temperature: options.temperature || 0.9
                });

                return completion.choices[0]?.message?.content || '';
            }

            // Обычный текстовый запрос
            messages.push({
                role: 'user',
                content: prompt
            });

            const completion = await this.client.chat.completions.create({
                model: this.model,
                messages: messages,
                max_tokens: options.maxTokens || 2048,
                temperature: options.temperature || 0.9,
                // Для JSON ответов
                response_format: options.jsonMode ? { type: 'json_object' } : undefined
            });

            let text = completion.choices[0]?.message?.content || '';

            // Очистка JSON обёрток
            if (options.expectJson) {
                text = text.replace(/```json/g, '').replace(/```/g, '').trim();
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
     * Groq не использует systemInstruction как Gemini,
     * вместо этого добавляем system message в историю
     */
    async generateWithSystem(systemPrompt, userPrompt, options = {}) {
        if (!this.isAvailable()) {
            throw new Error('Groq: Провайдер недоступен (нет ключей)');
        }

        const apiCall = async () => {
            const messages = [
                {
                    role: 'system',
                    content: systemPrompt
                },
                {
                    role: 'user',
                    content: userPrompt
                }
            ];

            const completion = await this.client.chat.completions.create({
                model: this.model,
                messages: messages,
                max_tokens: options.maxTokens || 2048,
                temperature: options.temperature || 0.9
            });

            return completion.choices[0]?.message?.content || '';
        };

        return await this.executeWithRetry(apiCall);
    }
}

module.exports = GroqProvider;

