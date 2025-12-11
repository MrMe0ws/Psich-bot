const axios = require('axios');
const BaseProvider = require('./base');

class DeepSeekProvider extends BaseProvider {
    constructor(keys) {
        super('DeepSeek', keys);
        this.model = 'deepseek-chat'; // Основная модель DeepSeek
        this.baseURL = 'https://api.deepseek.com';
    }

    supportsVision() {
        return false; // DeepSeek Chat не поддерживает vision в текущей версии
    }

    supportsSearch() {
        return false; // DeepSeek не поддерживает встроенный поиск
    }

    async generate(prompt, options = {}) {
        if (!this.isAvailable()) {
            throw new Error('DeepSeek: Провайдер недоступен (нет ключей)');
        }

        const apiCall = async () => {
            const currentKey = this.getCurrentKey();
            if (!currentKey) {
                throw new Error('DeepSeek: Нет доступного ключа');
            }

            const messages = [];

            // Если есть системный промпт в опциях
            if (options.systemPrompt) {
                messages.push({
                    role: 'system',
                    content: options.systemPrompt
                });
            }

            // Если есть медиа (DeepSeek пока не поддерживает, но оставим структуру)
            if (options.mediaBuffer && options.mimeType) {
                // DeepSeek Chat не поддерживает изображения, выбрасываем ошибку
                throw new Error('DeepSeek: Vision не поддерживается в текущей версии');
            }

            // Обычный текстовый запрос
            messages.push({
                role: 'user',
                content: prompt
            });

            let response;
            try {
                response = await axios.post(
                    `${this.baseURL}/chat/completions`,
                    {
                        model: this.model,
                        messages: messages,
                        max_tokens: options.maxTokens || 2500,
                        temperature: options.temperature || 0.9,
                        stream: false
                    },
                    {
                        headers: {
                            'Content-Type': 'application/json',
                            'Authorization': `Bearer ${currentKey}`
                        }
                    }
                );
            } catch (axiosError) {
                // Обработка ошибок axios
                if (axiosError.response) {
                    const status = axiosError.response.status;
                    const errorData = axiosError.response.data;
                    const errorMsg = errorData?.error?.message || errorData?.message || axiosError.message;

                    if (status === 429) {
                        throw new Error(`DeepSeek: Rate limit exceeded (429). ${errorMsg}`);
                    } else if (status === 402) {
                        // Недостаточно баланса - это тоже лимит
                        throw new Error(`DeepSeek: Insufficient balance (402). ${errorMsg}`);
                    } else if (status === 401) {
                        throw new Error(`DeepSeek: Invalid API key (401)`);
                    } else if (status === 400) {
                        throw new Error(`DeepSeek: Bad request (400). ${errorMsg}`);
                    } else {
                        throw new Error(`DeepSeek: API error (${status}). ${errorMsg}`);
                    }
                } else if (axiosError.request) {
                    throw new Error(`DeepSeek: Network error - no response from server`);
                } else {
                    throw new Error(`DeepSeek: ${axiosError.message}`);
                }
            }

            if (!response.data || !response.data.choices || response.data.choices.length === 0) {
                throw new Error('DeepSeek: Пустой ответ от API');
            }

            let text = response.data.choices[0]?.message?.content || '';

            if (!text || text.trim().length === 0) {
                throw new Error('DeepSeek: Ответ пустой');
            }

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
     * DeepSeek поддерживает system message через messages
     */
    async generateWithSystem(systemPrompt, userPrompt, options = {}) {
        return await this.generate(userPrompt, {
            ...options,
            systemPrompt: systemPrompt
        });
    }
}

module.exports = DeepSeekProvider;
