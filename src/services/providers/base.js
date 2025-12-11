/**
 * Базовый абстрактный класс для AI провайдеров
 */
class BaseProvider {
    constructor(name, keys) {
        this.name = name;
        this.keys = keys || [];
        this.currentKeyIndex = 0;

        if (this.keys.length === 0) {
            console.warn(`[${this.name}] Нет ключей, провайдер отключен`);
        }
    }

    /**
     * Проверка доступности провайдера
     */
    isAvailable() {
        return this.keys.length > 0;
    }

    /**
     * Получить текущий ключ
     */
    getCurrentKey() {
        if (this.keys.length === 0) return null;
        return this.keys[this.currentKeyIndex];
    }

    /**
     * Переключить на следующий ключ
     */
    rotateKey() {
        if (this.keys.length === 0) return false;
        this.currentKeyIndex = (this.currentKeyIndex + 1) % this.keys.length;
        console.log(`[${this.name}] Переключение на ключ #${this.currentKeyIndex + 1}`);
        return true;
    }

    /**
     * Проверка - это ошибка квоты/лимита?
     */
    isQuotaError(error) {
        const msg = error.message.toLowerCase();
        return msg.includes('429') ||
            msg.includes('quota') ||
            msg.includes('exhausted') ||
            msg.includes('limit') ||
            msg.includes('rate');
    }

    /**
     * Основной метод генерации (должен быть переопределен)
     */
    async generate(prompt, options = {}) {
        throw new Error(`${this.name}: метод generate() должен быть реализован в наследнике`);
    }

    /**
     * Проверка поддержки vision
     */
    supportsVision() {
        return false;
    }

    /**
     * Проверка поддержки search
     */
    supportsSearch() {
        return false;
    }

    /**
     * Выполнение с ротацией ключей
     */
    async executeWithRetry(apiCallFn) {
        if (this.keys.length === 0) {
            throw new Error(`${this.name}: Нет доступных ключей`);
        }

        const maxAttempts = this.keys.length;

        for (let attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return await apiCallFn();
            } catch (error) {
                if (this.isQuotaError(error)) {
                    // Пытаемся парсить время ожидания из ошибки
                    // Пример: "Please retry in 32.646402495s."
                    let waitTime = 1000; // 1 секунда по умолчанию
                    let retrySeconds = null;
                    const match = error.message.match(/retry in (\d+(\.\d+)?)s/);
                    if (match && match[1]) {
                        retrySeconds = parseFloat(match[1]);
                        // Если нужно ждать больше 30 секунд - это дневной лимит, переключаемся на другой провайдер
                        if (retrySeconds > 30) {
                            console.log(`[${this.name}] Дневной лимит исчерпан (нужно ждать ${retrySeconds}с). Переключаюсь на другой провайдер...`);
                            throw new Error(`${this.name}: Дневной лимит исчерпан`);
                        }
                        // Для коротких ожиданий (rate limit) ждем указанное время, но не больше 1 секунды
                        waitTime = Math.min(Math.ceil(retrySeconds * 1000), 1000);
                    }

                    console.log(`[${this.name}] Ошибка лимита: "${error.message.substring(0, 100)}...". Ждем ${waitTime / 1000} сек...`);
                    await new Promise(resolve => setTimeout(resolve, waitTime));

                    if (attempt < maxAttempts - 1) {
                        this.rotateKey();
                        continue;
                    } else {
                        // Все ключи исчерпаны
                        throw new Error(`${this.name}: Все ключи исчерпали лимит`);
                    }
                }
                throw error;
            }
        }

        throw new Error(`${this.name}: Все ключи исчерпали лимит`);
    }
}

module.exports = BaseProvider;

