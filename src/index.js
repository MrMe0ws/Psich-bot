const TelegramBot = require('node-telegram-bot-api');
const config = require('./config');
const logic = require('./core/logic');
const storage = require('./services/storage');

// Создаем бота
const bot = new TelegramBot(config.telegramToken, { polling: true });

console.log("Псич запущен и готов пояснять за жизнь.");
console.log(`Admin IDs: ${config.adminIds.join(', ')}`);

// === ТИКЕР НАПОМИНАЛОК (Проверка каждую минуту) ===
setInterval(() => {
  const pending = storage.getPendingReminders();

  if (pending.length > 0) {
    console.log(`[REMINDER] Сработало напоминаний: ${pending.length}`);

    const idsToRemove = [];

    pending.forEach(task => {
      // Формируем сообщение
      const message = `⏰ ${task.username}, напоминаю!\n\n${task.text}`;

      // Отправляем
      bot.sendMessage(task.chatId, message).then(() => {
        console.log(`[REMINDER] Успешно отправлено: ${task.text}`);
      }).catch(err => {
        console.error(`[REMINDER ERROR] Не смог отправить в ${task.chatId}: ${err.message}`);
        // Если юзер заблочил бота, все равно удаляем, чтобы не спамить в лог ошибками
      });

      idsToRemove.push(task.id);
    });

    // Чистим базу
    storage.removeReminders(idsToRemove);
  }
}, 60 * 1000); // 60000 мс = 1 минута

// Обработка ошибок поллинга
bot.on('polling_error', (error) => {
  console.error(`[POLLING ERROR] ${error.code}: ${error.message}`);
  // Если ошибка "Conflict: terminated by other getUpdates", значит запущен второй экземпляр
});

// === 🛡 SECURITY PROTOCOL: "ВЕРНЫЙ ОРУЖЕНОСЕЦ" ===
// Функция проверки наличия админа в чате
async function checkAdminInChat(chatId) {
  let hasAdmin = false;
  for (const adminId of config.adminIds) {
    try {
      const adminMember = await bot.getChatMember(chatId, adminId);
      const allowedStatuses = ['creator', 'administrator', 'member'];
      if (allowedStatuses.includes(adminMember.status)) {
        hasAdmin = true;
        break;
      }
    } catch (e) {
      // Если не можем проверить конкретного админа, продолжаем проверять других
      continue;
    }
  }
  return hasAdmin;
}

// Единый вход для всех сообщений
bot.on('message', async (msg) => {
  // Игнорируем сообщения, старше 2 минут (чтобы не отвечать на старое при рестарте)
  const now = Math.floor(Date.now() / 1000);
  if (msg.date < now - 120) return;

  const chatId = msg.chat.id;
  const chatTitle = msg.chat.title || "Личка";

  // === ОБРАБОТКА ДОБАВЛЕНИЯ БОТА В ГРУППУ ===
  if (msg.chat.type !== 'private' && msg.new_chat_members && msg.new_chat_members.some(u => u.id === config.botId)) {
    console.log(`[SECURITY] Бот добавлен в группу "${chatTitle}"`);

    try {
      const hasAdmin = await checkAdminInChat(chatId);

      if (!hasAdmin) {
        console.log(`[SECURITY] ⛔ В группе "${chatTitle}" нет админа. Ухожу.`);
        const phrases = [
          "Так, стопэ. Админа не вижу. Благотворительности не будет, я уёбываю!",
          "Опа, куда это меня занесло? Бати рядом нет, так что я уёбываю!",
          "Вы че думали, украли бота? Я не работаю в беспризорных приютах. Я уёбываю!",
          "⚠️ ERROR: ADMIN NOT FOUND. Включаю протокол самоуважения. Я уёбываю!",
          "Не, ну вы видели? Затащили без спроса. Ну вас нахер, я уёбываю!"
        ];
        const randomPhrase = phrases[Math.floor(Math.random() * phrases.length)];

        await bot.sendMessage(chatId, randomPhrase).catch(() => { });
        await bot.leaveChat(chatId).catch(() => { });
        return;
      } else {
        console.log(`[SECURITY] ✅ В группе "${chatTitle}" найден админ. Остаюсь.`);
      }
    } catch (e) {
      console.error(`[SECURITY ERROR] Ошибка проверки админа при добавлении в "${chatTitle}": ${e.message}`);
      // При ошибке лучше уйти, чтобы не остаться в группе без админа
      await bot.leaveChat(chatId).catch(() => { });
      return;
    }
  }

  // === ПРОВЕРКА НАЛИЧИЯ АДМИНА ПРИ ОБЫЧНЫХ СООБЩЕНИЯХ ===
  // Проверяем наличие хотя бы одного Админа в ЛЮБОМ групповом чате при ЛЮБОМ сообщении
  if (msg.chat.type !== 'private') {
    try {
      const hasAdmin = await checkAdminInChat(chatId);

      // Если ни одного Админа нет
      if (!hasAdmin) {
        console.log(`[SECURITY] ⛔ Обнаружен чат без Админа...`);

        const phrases = [
          "Так, стопэ. Админа не вижу. Благотворительности не будет, я уёбываю!",
          "Опа, куда это меня занесло? Бати рядом нет, так что я уёбываю!",
          "Вы че думали, украли бота? Я не работаю в беспризорных приютах. Я уёбываю!",
          "⚠️ ERROR: ADMIN NOT FOUND. Включаю протокол самоуважения. Я уёбываю!",
          "Не, ну вы видели? Затащили без спроса. Ну вас нахер, я уёбываю!"
        ];
        const randomPhrase = phrases[Math.floor(Math.random() * phrases.length)];

        await bot.sendMessage(chatId, randomPhrase).catch(() => { });
        await bot.leaveChat(chatId).catch(() => { });
        return;
      }
    } catch (e) {
      // Если мы даже не можем проверить админа (например, бот забанен или нет прав), лучше уйти
      console.error(`[SECURITY ERROR] Ошибка проверки прав в "${chatTitle}": ${e.message}`);
      // На всякий случай пытаемся выйти, если ошибка критичная
      if (e.message.includes('chat not found') || e.message.includes('kicked')) {
        // Игнорим, мы и так не там
      } else {
        // Пытаемся выйти
        bot.leaveChat(chatId).catch(() => { });
      }
    }
  }

  // === ЛОГИКА ВЫХОДА ВСЛЕД ЗА АДМИНОМ (ХАТИКО) ===
  // Уходим, если ушел последний админ
  if (msg.left_chat_member && config.isAdmin(msg.left_chat_member.id)) {
    // Проверяем, остался ли еще хотя бы один админ
    let hasRemainingAdmin = false;
    if (msg.chat.type !== 'private') {
      for (const adminId of config.adminIds) {
        if (adminId === msg.left_chat_member.id) continue; // Пропускаем того, кто ушел
        try {
          const adminMember = await bot.getChatMember(chatId, adminId);
          const allowedStatuses = ['creator', 'administrator', 'member'];
          if (allowedStatuses.includes(adminMember.status)) {
            hasRemainingAdmin = true;
            break;
          }
        } catch (e) {
          continue;
        }
      }
    }

    if (!hasRemainingAdmin) {
      console.log(`[SECURITY] Последний админ вышел из чата "${chatTitle}". Ухожу следом.`);
      await bot.sendMessage(chatId, "Батя ушел, и я сваливаю.");
      await bot.leaveChat(chatId);
      return;
    }
  }

  // Дальше идет обычная логика...
  await logic.processMessage(bot, msg);
});

// Сохраняем базу при выходе
process.on('SIGINT', () => {
  console.log("Сохранение данных перед выходом...");
  storage.forceSave();
  process.exit();
});