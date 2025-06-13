import os
from dotenv import load_dotenv
import logging

# Загрузка переменных окружения
load_dotenv()

# Настройка логирования
root_logger = logging.getLogger()
root_logger.setLevel(logging.INFO)

# Обработчик для файла (только ERROR и выше)
file_handler = logging.FileHandler("server.log")
file_handler.setLevel(logging.ERROR)
file_handler.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))

# Обработчик для консоли (INFO и выше)
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
console_handler.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))

# Добавляем обработчики в корневой логгер
root_logger.addHandler(file_handler)
root_logger.addHandler(console_handler)

# Получаем логгер для текущего модуля
logger = logging.getLogger(__name__)

# Конфигурация приложения
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
DB_USER = os.getenv("DB_USER")
DB_PASSWORD = os.getenv("DB_PASSWORD")
DB_NAME = os.getenv("DB_NAME")
DB_HOST = os.getenv("DB_HOST")

# Проверка конфигурации
if not OPENAI_API_KEY:
    logger.error("OPENAI_API_KEY is not set in .env file")
    raise ValueError("OPENAI_API_KEY is required")

if not all([DB_USER, DB_PASSWORD, DB_NAME, DB_HOST]):
    logger.error("Database configuration is incomplete in .env file")
    raise ValueError("Database configuration is required")