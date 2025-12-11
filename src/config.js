require('dotenv').config();

// Собираем все ключи Gemini в массив
const geminiKeys = [];
if (process.env.GOOGLE_GEMINI_API_KEY && process.env.GOOGLE_GEMINI_API_KEY.trim()) {
  geminiKeys.push(process.env.GOOGLE_GEMINI_API_KEY.trim());
}

// Ищем ключи с суффиксами _2, _3 и т.д. (можно добавлять сколько угодно)
let i = 2;
while (process.env[`GOOGLE_GEMINI_API_KEY_${i}`]) {
  const key = process.env[`GOOGLE_GEMINI_API_KEY_${i}`].trim();
  if (key) { // Пропускаем пустые ключи
    geminiKeys.push(key);
  }
  i++;
}

console.log(`[CONFIG] Загружено ключей Gemini: ${geminiKeys.length}`);

// Собираем ключи Groq
const groqKeys = [];
if (process.env.GROQ_API_KEY && process.env.GROQ_API_KEY.trim()) {
  groqKeys.push(process.env.GROQ_API_KEY.trim());
}

// Ищем ключи Groq с суффиксами _2, _3 и т.д. (можно добавлять сколько угодно)
let j = 2;
while (process.env[`GROQ_API_KEY_${j}`]) {
  const key = process.env[`GROQ_API_KEY_${j}`].trim();
  if (key) { // Пропускаем пустые ключи
    groqKeys.push(key);
  }
  j++;
}

console.log(`[CONFIG] Загружено ключей Groq: ${groqKeys.length}`);

// Собираем ключи DeepSeek
const deepseekKeys = [];
if (process.env.DEEPSEEK_API_KEY && process.env.DEEPSEEK_API_KEY.trim()) {
  deepseekKeys.push(process.env.DEEPSEEK_API_KEY.trim());
}

// Ищем ключи DeepSeek с суффиксами _2, _3 и т.д. (можно добавлять сколько угодно)
let k = 2;
while (process.env[`DEEPSEEK_API_KEY_${k}`]) {
  const key = process.env[`DEEPSEEK_API_KEY_${k}`].trim();
  if (key) { // Пропускаем пустые ключи
    deepseekKeys.push(key);
  }
  k++;
}

console.log(`[CONFIG] Загружено ключей DeepSeek: ${deepseekKeys.length}`);

// Парсим ID администраторов (поддерживаем запятую и пробел как разделители)
const parseAdminIds = (adminIdString) => {
  if (!adminIdString) return [];
  return adminIdString
    .split(/[,\s]+/)
    .map(id => parseInt(id.trim(), 10))
    .filter(id => !isNaN(id) && id > 0);
};

const adminIds = parseAdminIds(process.env.ADMIN_USER_ID);
const adminId = adminIds.length > 0 ? adminIds[0] : null; // Для обратной совместимости

if (adminIds.length === 0) {
  console.warn('[CONFIG] ВНИМАНИЕ: Не указан ADMIN_USER_ID!');
} else {
  console.log(`[CONFIG] Загружено администраторов: ${adminIds.length} (${adminIds.join(', ')})`);
}

// Функция для проверки, является ли пользователь администратором
const isAdmin = (userId) => {
  return adminIds.includes(userId);
};

module.exports = {
  telegramToken: process.env.TELEGRAM_BOT_TOKEN,
  botId: parseInt(process.env.TELEGRAM_BOT_TOKEN.split(':')[0], 10),
  adminId: adminId, // Для обратной совместимости (первый ID)
  adminIds: adminIds, // Массив всех ID
  isAdmin: isAdmin, // Функция проверки

  geminiKeys: geminiKeys,
  groqKeys: groqKeys,
  deepseekKeys: deepseekKeys,

  modelName: 'gemini-2.5-flash',
  contextSize: 30,
  // Regex для поиска "псич" или "psych" как отдельного слова
  // (?<![а-яёa-z]) - перед словом не должно быть буквы
  // (?![а-яёa-z]) - после слова не должно быть буквы
  // Это работает с кириллицей и латиницей
  triggerRegex: /(?<![а-яёa-z0-9_])(псич|psych)(?![а-яёa-z0-9_])/i,

};


