const AIManager = require('./ai-manager');
const config = require('../config');

// Создаем единственный экземпляр менеджера
const aiManager = new AIManager({
  geminiKeys: config.geminiKeys,
  groqKeys: config.groqKeys,
  deepseekKeys: config.deepseekKeys
});

// Экспортируем методы менеджера для обратной совместимости
module.exports = {
  getResponse: (history, currentMessage, imageBuffer, mimeType, userInstruction, userProfile, isSpontaneous) => {
    return aiManager.getResponse(history, currentMessage, imageBuffer, mimeType, userInstruction, userProfile, isSpontaneous);
  },

  determineReaction: (contextText) => {
    return aiManager.determineReaction(contextText);
  },

  analyzeUserImmediate: (lastMessages, currentProfile) => {
    return aiManager.analyzeUserImmediate(lastMessages, currentProfile);
  },

  analyzeBatch: (messagesBatch, currentProfiles) => {
    return aiManager.analyzeBatch(messagesBatch, currentProfiles);
  },

  generateProfileDescription: (profileData, targetName) => {
    return aiManager.generateProfileDescription(profileData, targetName);
  },

  generateFlavorText: (task, result) => {
    return aiManager.generateFlavorText(task, result);
  },

  shouldAnswer: (lastMessages) => {
    return aiManager.shouldAnswer(lastMessages);
  },

  transcribeAudio: (audioBuffer, userName, mimeType) => {
    return aiManager.transcribeAudio(audioBuffer, userName, mimeType);
  },

  parseReminder: (userText, contextText) => {
    return aiManager.parseReminder(userText, contextText);
  }
};
