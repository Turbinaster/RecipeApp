import os
import logging
import base64
import asyncpg
import asyncio
import re
import aiohttp
import aioredis
import time
import random
from aiogram.filters import Command
from urllib.parse import urlparse, parse_qs
from aiogram import Bot, Dispatcher, types, F
from aiogram import Router
from aiogram.types import (
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    LabeledPrice
)
from dotenv import load_dotenv
from PIL import Image
from io import BytesIO


# Создание пула соединений с базой данных
async def create_db_pool():
    return await asyncpg.create_pool(
        database="vpn_service",
        user="vpn_user",
        password="vpn_password",
        host="localhost"
    )

# Инициализация пула соединений (асинхронно в основном цикле программы)
db_pool = None

# Функция для логирования взаимодействий пользователей
async def log_interaction(telegram_id, interaction_type):
    async with db_pool.acquire() as connection:
        await connection.execute(
            """
            INSERT INTO user_interactions (telegram_id, interaction_type)
            VALUES ($1, $2)
            """,
            telegram_id, interaction_type
        )

# Загрузка переменных окружения из файла .env
load_dotenv()

# Инициализация подключения к Redis
redis = None

async def create_redis_pool():
    global redis
    redis = await aioredis.from_url('redis://localhost')

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler("bot.log"),
        logging.StreamHandler()
    ]
)

logger = logging.getLogger(__name__)

# Настройка заголовков для запросов к OpenAI API
OPENAI_API_KEY = os.getenv('OPENAI_API_KEY')
headers = {
    "Authorization": f"Bearer {OPENAI_API_KEY}",
    "Content-Type": "application/json"
}

# URL для GPT-4o API
GPT_API_URL = "https://api.openai.com/v1/chat/completions"

# Инициализация бота и диспетчера
BOT_TOKEN = os.getenv('TELEGRAM_BOT_TOKEN')
PROVIDER_TOKEN = '390540012:LIVE:56994'
bot = Bot(token=BOT_TOKEN)
dp = Dispatcher()

# Определяем router перед его использованием
router = Router()
dp.include_router(router)  # Подключаем router к Dispatcher


# Функция для исправления Markdown
def fix_markdown(text):
    # Проверка парности звёздочек (*)
    def fix_asterisks(text):
        # Если количество звёздочек нечётное
        if text.count('*') % 2 != 0:
            # Если звёздочка в начале текста, убираем её
            if text.startswith('*'):
                text = text.lstrip('*')
            else:
                # Если звёздочка не в начале, убираем последнюю звёздочку
                text = text.rstrip('*')
        return text

    # Проверка парности подчёркиваний (_)
    def fix_underscores(text):
        if text.count('_') % 2 != 0:
            text = text.rstrip('_')
        return text

    # Проверка парности квадратных скобок []
    def fix_brackets(text):
        if text.count('[') != text.count(']'):
            # Убираем лишние открытые скобки
            text = text.replace('[', '', text.count('[') - text.count(']'))
        return text

    # Проверка парности круглых скобок ()
    def fix_parentheses(text):
        if text.count('(') != text.count(')'):
            # Убираем лишние открытые скобки
            text = text.replace('(', '', text.count('(') - text.count(')'))
        return text

    # Удаление подчёркиваний внутри слов
    def remove_inline_underscores(text):
        return re.sub(r'(?<=\w)_(?=\w)', ' ', text)

    # Исправление лишних бэкслешей (\)
    def fix_backslashes(text):
        return re.sub(r'\\{2,}', r'\\', text)

    # Проверка парности спецсимволов для кодовых блоков и зачёркиваний
    def fix_special_chars(text):
        if text.count('`') % 2 != 0:
            text = text.rstrip('`')  # Убираем лишний знак кодового блока
        return text

    # Ограничение длины сообщения
    def truncate_long_text(text, max_length=4096):
        if len(text) > max_length:
            text = text[:max_length] + "..."
        return text

    # Применение всех проверок
    text = fix_asterisks(text)
    text = fix_underscores(text)
    text = fix_brackets(text)
    text = fix_parentheses(text)
    text = remove_inline_underscores(text)
    text = fix_backslashes(text)
    text = fix_special_chars(text)
    text = truncate_long_text(text)

    return text

# Функция для изменения размера изображения
def resize_image(image_bytes, max_size=(512, 512)):
    start_time = time.time()  # Начало замера времени
    image = Image.open(BytesIO(image_bytes))
    original_size = image.size
    image.thumbnail(max_size)
    resized_size = image.size
    output = BytesIO()
    image.save(output, format='JPEG')
    output.seek(0)
    end_time = time.time()  # Конец замера времени
    logger.info(f"Original size: {original_size}, Resized size: {resized_size}, Processing time: {end_time - start_time} seconds")
    return output.getvalue()

# Преобразование сжатого изображения в Base64
def convert_image_to_base64(image_bytes):
    return base64.b64encode(image_bytes).decode('utf-8')

# Функция для получения количества запросов
async def get_user_attempts(telegram_id):
    async with db_pool.acquire() as connection:
        result = await connection.fetchval(
            "SELECT attempts_left FROM photo_ai WHERE telegram_id = $1",
            telegram_id
        )
        return result

# Функция для обновления количества запросов
async def update_user_attempts(telegram_id):
    async with db_pool.acquire() as connection:
        await connection.execute(
            """
            UPDATE photo_ai
            SET attempts_left = attempts_left + 1, last_activity = CURRENT_TIMESTAMP
            WHERE telegram_id = $1 AND attempts_left > -1
            """,
            telegram_id
        )

# Асинхронная функция для отправки запроса в OpenAI API
async def send_to_openai_async(text, image_base64=None):
    # Формирование сообщений в соответствии с примером из документации
    content = [
        {
            "type": "text",
            "text": text
        }
    ]
    if image_base64:
        content.append(
            {
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/jpeg;base64,{image_base64}"
                }
            }
        )

    json_body = {
        "model": "gpt-4o",  # Корректное название модели
        "messages": [
            {
                "role": "user",
                "content": content
            }
        ],
        "max_tokens": 1500
    }

    text_prompt = json_body['messages'][0]['content'][0]['text']
    logger.info(f"Текст запроса: {text_prompt}")

    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(GPT_API_URL, headers=headers, json=json_body) as response:
                logger.info(f"Ответ от OpenAI API: статус {response.status}")
                if response.status == 200:
                    data = await response.json()
                    logger.info("Ответ успешно получен.")
                    return data
                else:
                    text_response = await response.text()
                    logger.error(f"Ошибка от OpenAI: {response.status}, {text_response}")
                    return None
    except aiohttp.ClientError as e:
        logger.error(f"Ошибка при отправке запроса в OpenAI API: {e}")
        return None
        
# Функция проверки наличия пользователя в базе
async def user_exists(telegram_id):
    async with db_pool.acquire() as connection:
        result = await connection.fetchval(
            "SELECT COUNT(*) FROM photo_ai WHERE telegram_id = $1", telegram_id
        )
        return result > 0

# Функция создания пользователя
async def create_user(telegram_id):
    async with db_pool.acquire() as connection:
        await connection.execute(
            """
            INSERT INTO photo_ai (telegram_id, registration_date, attempts_left, last_activity)
            VALUES ($1, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)
            ON CONFLICT (telegram_id) DO NOTHING
            """,
            telegram_id
        )

async def is_user_subscribed(bot, user_id, channel_id):
    try:
        member = await bot.get_chat_member(chat_id=channel_id, user_id=user_id)
        # Проверяем статус подписки. Если пользователь подписан, то статус либо 'member', либо 'administrator', либо 'creator'.
        return member.status in ['member', 'administrator', 'creator']
    except Exception as e:
        logger.error(f"Ошибка при проверке подписки: {e}")
        return False

# Обработчик команды /start
@router.message(F.text.startswith('/start'))
async def send_welcome(message: types.Message):
    # Проверяем, если это не личный чат, игнорируем
    if message.chat.type != 'private':
        return

    telegram_id = message.from_user.id
    user_name = message.from_user.first_name

    # Проверяем, существует ли пользователь
    user_already_exists = await user_exists(telegram_id)

    try:
        if not user_already_exists:
            logger.info("Creating user...")
            await create_user(telegram_id)  # Создаем пользователя
            logger.info("User created successfully.")
        else:
            logger.info(f"User {telegram_id} already exists.")
    except Exception as e:
        logger.error(f"Error creating user: {e}")
        return  # Завершаем выполнение функции, если возникла ошибка

    try:
        logger.info(f"Fetching attempts left for user {telegram_id}...")
        attempts_left = await get_user_attempts(telegram_id)
        logger.info(f"Attempts left for user {telegram_id}: {attempts_left}")
    except Exception as e:
        logger.error(f"Error fetching attempts: {e}")
        return

    # Удаляем сообщение /start или /help
    await message.delete()

    # Отправляем приветственное сообщение
    msg = await message.answer(
        f"*Привет, {user_name}!* \n\n"
        f"👨‍🍳 Я - твой надёжный кулинарный помощник!\n\n"
        f"🥘 Отправь мне фото любого блюда,— и я пришлю его пошаговый рецепт!\n\n"
        f"ℹ️ Можно добавить подпись к фото, если нужно что-то уточнить. \n\n"
        f"🥩🥕 Отправь мне фото продуктов, которые есть под рукой,- и я подскажу, что можно из них приготовить!\n\n"
        f"🗣 Я понимаю речь! Отправь мне голосовое с любым вопросом кулинарной тематики. \n\n"
        f"📝 Или можешь просто написать мне свой вопрос.\n"
        f"Например: \"Что приготовить из фарша и грибов?\" или \"Как правильно разделать курицу?\"\n\n"
        f"👀 Подсмотреть, что готовят другие, можно здесь:\n"
        f"@PhotoRecipeStream — это лента, где автоматически публикуются рецепты по фото всех пользователей.\n\n"
        f"🥗 А здесь я каждый день публикую интересные факты про еду с рецептами:\n"
        f"@Kulinarka - рекомендую подписаться!\n\n"
        f"Давай готовить!",
        parse_mode="Markdown",
    )

    # Сохраняем ID приветственного сообщения
    await redis.hset(f"user:{telegram_id}:last_message", mapping={
        'last_message_id': msg.message_id,
        'last_message_type': 'other'
    })
    logger.info(f"Приветственное сообщение отправлено. ID: {msg.message_id}")

# Обработчик команды /users
@router.message(F.text == "/users")
async def count_users(message: types.Message):
    telegram_id = message.from_user.id

    # Проверяем, что команда доступна только пользователю с ID 65164172
    if telegram_id != 65164172:
        await message.answer("Эта команда вам недоступна.")
        return

    # Выполняем запрос в базу данных для подсчета всех пользователей
    async with db_pool.acquire() as connection:
        total_users = await connection.fetchval("SELECT COUNT(*) FROM photo_ai")

    # Отправляем сообщение с количеством пользователей
    await message.answer(f"Общее количество пользователей в базе: {total_users}")

# Обработчик команды /messageall
@router.message(F.text.startswith("/messageall") | (F.photo & F.caption.startswith("/messageall")))
async def send_message_to_all_users(message: types.Message):
    telegram_id = message.from_user.id
    ADMIN_ID = 65164172  # ID админа

    # Проверяем, что команда доступна только пользователю с ADMIN_ID
    if telegram_id != ADMIN_ID:
        await message.answer("Эта команда вам недоступна.")
        return

    # Подсчёт успешных сообщений
    successful_messages = 0

    async with db_pool.acquire() as connection:
        users = await connection.fetch("SELECT telegram_id FROM photo_ai")

    # 📸 Обработка изображения с подписью
    if message.photo:
        photo = message.photo[-1]  # Берём изображение в наивысшем качестве
        caption = message.caption[len('/messageall'):].strip() if message.caption else "Нет подписи"

        # ❗️ НЕ применяем fix_markdown, так как администратор уже может использовать корректное форматирование
        for user in users:
            try:
                await bot.send_photo(
                    chat_id=user['telegram_id'],
                    photo=photo.file_id,
                    caption=caption,
                    parse_mode="Markdown"
                )
                successful_messages += 1  # Увеличиваем счётчик при успешной отправке
            except Exception as e:
                logger.error(f"Ошибка при отправке изображения пользователю {user['telegram_id']}: {e}")

        # Подтверждение админу
        await message.answer(f"Изображение успешно отправлено {successful_messages} пользователям!")
        logger.info(f"Изображение отправлено {successful_messages} пользователям.")
        return

    # ✉️ Обработка текстового сообщения
    text_to_send = message.text[len('/messageall '):].strip()

    if not text_to_send:
        await message.answer("Пожалуйста, добавьте текст или изображение после команды `/messageall`.")
        return

    # ❗️ НЕ применяем fix_markdown, так как администратор уже может использовать корректное форматирование
    for user in users:
        try:
            await bot.send_message(
                chat_id=user['telegram_id'],
                text=text_to_send,
                parse_mode="Markdown"
            )
            successful_messages += 1  # Увеличиваем счётчик при успешной отправке
        except Exception as e:
            logger.error(f"Ошибка при отправке сообщения пользователю {user['telegram_id']}: {e}")

    # Подтверждение админу
    await message.answer(f"Сообщение успешно отправлено {successful_messages} пользователям!")
    logger.info(f"Текстовое сообщение отправлено {successful_messages} пользователям.")

# Обработчик для команды /messagetest (текст и фото с подписью)
@router.message(F.text.startswith("/messagetest") | (F.photo & F.caption.startswith("/messagetest")))
async def send_test_message_to_admin(message: types.Message):
    telegram_id = message.from_user.id
    ADMIN_ID = 65164172  # ID админа

    # Проверка прав администратора
    if telegram_id != ADMIN_ID:
        await message.answer("Эта команда вам недоступна.")
        return

    # Обработка изображения с подписью
    if message.photo:
        photo = message.photo[-1]  # Берём изображение в наилучшем качестве
        caption = message.caption[len('/messagetest'):].strip() if message.caption else "Нет подписи"

        try:
            await bot.send_photo(
                chat_id=ADMIN_ID,
                photo=photo.file_id,
                caption=caption,
                parse_mode="Markdown"
            )
            logger.info(f"Тестовое изображение с подписью отправлено админу ({ADMIN_ID}).")
            await message.answer("Тестовое изображение с подписью успешно отправлено админу!")
        except Exception as e:
            logger.error(f"Ошибка при отправке изображения админу ({ADMIN_ID}): {e}")
            await message.answer("Произошла ошибка при отправке изображения.")
        return

    # Обработка обычного текстового сообщения
    text_to_send = message.text[len('/messagetest'):].strip()

    if not text_to_send:
        await message.answer("Пожалуйста, добавьте текст после команды `/messagetest`.")
        return

    try:
        await bot.send_message(
            ADMIN_ID,
            text_to_send,
            parse_mode="Markdown"
        )
        logger.info(f"Тестовое сообщение успешно отправлено админу ({ADMIN_ID}).")
        await message.answer("Тестовое сообщение успешно отправлено админу!")
    except Exception as e:
        logger.error(f"Ошибка при отправке текстового сообщения админу ({ADMIN_ID}): {e}")
        await message.answer("Произошла ошибка при отправке текстового сообщения.")

# Обработчик команды /stats
@router.message(F.text == "/stats")
async def get_stats(message: types.Message):
    telegram_id = message.from_user.id
    ADMIN_ID = 65164172  # ID админа

    # Проверяем, что команда доступна только администратору
    if telegram_id != ADMIN_ID:
        await message.answer("Эта команда вам недоступна.")
        return

    async with db_pool.acquire() as connection:
        # Статистика за последние 24 часа
        users_24h = await connection.fetchval(
            """
            SELECT COUNT(DISTINCT telegram_id)
            FROM user_interactions
            WHERE interaction_time >= NOW() - INTERVAL '24 hours'
            """
        )

        photos_24h = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'photo' AND interaction_time >= NOW() - INTERVAL '24 hours'
            """
        )

        messages_24h = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'message' AND interaction_time >= NOW() - INTERVAL '24 hours'
            """
        )

        voices_24h = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'voice' AND interaction_time >= NOW() - INTERVAL '24 hours'
            """
        )

        # Статистика за последние 30 дней
        users_30d = await connection.fetchval(
            """
            SELECT COUNT(DISTINCT telegram_id)
            FROM user_interactions
            WHERE interaction_time >= NOW() - INTERVAL '30 days'
            """
        )

        photos_30d = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'photo' AND interaction_time >= NOW() - INTERVAL '30 days'
            """
        )

        messages_30d = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'message' AND interaction_time >= NOW() - INTERVAL '30 days'
            """
        )

        voices_30d = await connection.fetchval(
            """
            SELECT COUNT(*)
            FROM user_interactions
            WHERE interaction_type = 'voice' AND interaction_time >= NOW() - INTERVAL '30 days'
            """
        )

    # Формируем сообщение со статистикой
    stats_message = (
        f"📊 *Статистика за последние 24 часа:*\n\n"
        f"👥 Уникальных пользователей: {users_24h}\n"
        f"🖼 Фото отправлено: {photos_24h}\n"
        f"✉️ Текстовых сообщений отправлено: {messages_24h}\n"
        f"🎙️ Голосовых сообщений отправлено: {voices_24h}\n\n"
        
        f"📊 *Статистика за последние 30 дней:*\n\n"
        f"👥 Уникальных пользователей: {users_30d}\n"
        f"🖼 Фото отправлено: {photos_30d}\n"
        f"✉️ Текстовых сообщений отправлено: {messages_30d}\n"
        f"🎙️ Голосовых сообщений отправлено: {voices_30d}"
    )

    # Отправляем статистику админу
    await message.answer(stats_message, parse_mode="Markdown")

# Обработчик текстового запроса пользователя
@router.message(F.text & ~F.text.startswith('/'))
async def handle_product_list(message: types.Message):
    # Проверяем, если это не личный чат, бот игнорирует сообщение
    if message.chat.type != 'private':
        return

    telegram_id = message.from_user.id
    channel_id = -1002469692233  # ID канала для проверки подписки

    # Проверяем подписку и attempts_left
    is_subscribed = await is_user_subscribed(bot, telegram_id, channel_id)
    attempts_left = await get_user_attempts(telegram_id)

    # Если пользователь не подписан и attempts_left > 4, отправляем сообщение с просьбой подписаться
    if not is_subscribed and attempts_left > 4:
        await message.answer(
            "Пожалуйста, подпишитесь на канал @kulinarka, чтобы продолжить использовать бота."
        )
        return

    # Логируем взаимодействие для статистики
    await log_interaction(telegram_id, 'message')

    product_list = message.text.strip()

    # Логируем исходный текст
    logger.info(f"Текст перед fix_markdown: {product_list}")

    # Применяем fix_markdown
    fixed_product_list = fix_markdown(product_list)

    # Логируем исправленный текст
    logger.info(f"Текст после fix_markdown: {fixed_product_list}")

    # Проверка количества оставшихся попыток
    if attempts_left < 0:
        msg = await message.answer(
            "Кажется, мне запретили с тобой общаться 🥺\nСвяжись с администратором, если считаешь что это ошибка."
        )
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'buttons_message_id': msg.message_id,
            'buttons_message_type': 'other'
        })
        return

    # Отправляем сообщение "⏳ Пишу ответ..." в ответ на сообщение пользователя
    processing_message = await message.reply("⏳ Пишу ответ...")

    try:
        # Формируем запрос для OpenAI
        gpt_prompt = f"""
        Если MESSAGE = продукт или набор продуктов, то скажи, какое блюдо можно из них приготовить.
        Сначала дай краткое описание, затем напиши пошаговый рецепт приготовления.

        Если MESSAGE = название блюда, то дай его краткое описание, затем напиши пошаговый рецепт приготовления.

        Если MESSAGE = любой вопрос связанный с кулинарией, то ответь на него. Но не отвечай на вопросы не связанные с кулинарией.

        Обязательные правила для форматирования рецепта:
        Перечень ингредиентов промаркируй жирной точкой (•)
        Заголовок КАЖДОГО нового абзаца отформатируй *жирным* текстом только *одинарными* звёздочками.
        Не используй другие способы форматирования.
        Укажи в конце калорийность в килокалориях (Ккал)

        Не здоровайся, но общайся легко и непринужденно, с лёгким юмором.
        Если один или несколько моих продуктов не годятся для готовки,- интересно обыграй это. 
        Начни сообщение с этого эмодзи:📝

        MESSAGE = {fixed_product_list}
        """

        # Отправляем запрос к OpenAI
        gpt_data = await send_to_openai_async(text=gpt_prompt)

        # Извлекаем ответ от OpenAI
        if gpt_data and "choices" in gpt_data and gpt_data["choices"]:
            response = gpt_data["choices"][0].get("message", {}).get("content", "Не удалось получить ответ.")
        else:
            response = "Не удалось получить ответ от OpenAI."

        # Редактируем сообщение "⏳ Пишу ответ..." на полученный ответ от OpenAI
        await processing_message.edit_text(response, parse_mode="Markdown")

        # Увеличиваем attempts_left после успешного запроса
        await update_user_attempts(telegram_id)

    except Exception as e:
        logger.error(f"Ошибка при запросе к OpenAI: {e}")
        await processing_message.edit_text("Произошла ошибка при обработке запроса. Попробуйте ещё раз позже.")

# Обработчик для голосовых сообщений
@router.message(F.voice)
async def handle_voice_message(message: types.Message):
    # Проверяем, если это не личный чат, бот игнорирует сообщение
    if message.chat.type != 'private':
        return

    telegram_id = message.from_user.id
    channel_id = -1002469692233  # ID канала для проверки подписки

    # Проверяем подписку и attempts_left
    is_subscribed = await is_user_subscribed(bot, telegram_id, channel_id)
    attempts_left = await get_user_attempts(telegram_id)

    # Если пользователь не подписан и attempts_left > 4, отправляем сообщение с просьбой подписаться
    if not is_subscribed and attempts_left > 4:
        await message.answer(
            "Пожалуйста, подпишитесь на канал @kulinarka, чтобы продолжить использовать бота."
        )
        return

    # Логируем взаимодействие для статистики
    await log_interaction(telegram_id, 'voice')

    # Проверка количества оставшихся попыток
    if attempts_left < 0:
        msg = await message.answer(
            "Кажется, мне запретили с тобой общаться 🥺\nСвяжись с администратором, если считаешь что это ошибка."
        )
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'last_message_id': msg.message_id,
            'last_message_type': 'other'
        })
        return

    # Проверяем длительность голосового сообщения
    if message.voice.duration > 60:
        await message.reply("Извините, голосовое сообщение слишком длинное. Пожалуйста, отправьте запись длиной не более 1 минуты.")
        return

    # Отправляем сообщение "⏳ Пишу ответ..." в ответ на голосовое сообщение пользователя
    processing_message = await message.reply("⏳ Пишу ответ...")

    try:
        # Получаем файл голосового сообщения
        voice = await bot.get_file(message.voice.file_id)
        voice_file_path = voice.file_path
        voice_url = f"https://api.telegram.org/file/bot{BOT_TOKEN}/{voice_file_path}"

        # Скачиваем голосовое сообщение
        async with aiohttp.ClientSession() as session:
            async with session.get(voice_url) as response:
                if response.status != 200:
                    await processing_message.edit_text("Не удалось загрузить голосовое сообщение.")
                    return
                voice_bytes = await response.read()

        # Отправляем аудио на OpenAI для транскрипции
        transcription = await transcribe_audio(voice_bytes)
        if not transcription:
            await processing_message.edit_text("Не удалось расшифровать голосовое сообщение. Попробуйте ещё раз.")
            return

        # Логируем транскрипцию голосового сообщения
        logger.info(f"Пользователь {telegram_id} отправил голосовое сообщение. Транскрипция: {transcription}")

        # Применяем fix_markdown к транскрипции
        fixed_product_list = fix_markdown(transcription)

        # Отправляем запрос к OpenAI
        gpt_prompt = f"""
        Если MESSAGE = продукт или набор продуктов, то скажи, какое блюдо можно из них приготовить.
        Сначала дай краткое описание, затем напиши пошаговый рецепт приготовления.

        Если MESSAGE = название блюда, то дай его краткое описание, затем напиши пошаговый рецепт приготовления.

        Если MESSAGE = любой вопрос связанный с кулинарией, то ответь на него. Но не отвечай на вопросы не связанные с кулинарией.

        Обязательные правила для форматирования рецепта:
        Перечень ингредиентов промаркируй жирной точкой (•)
        Заголовок КАЖДОГО нового абзаца отформатируй *жирным* текстом только *одинарными* звёздочками.
        Не используй другие способы форматирования.
        Укажи в конце калорийность в килокалориях (Ккал)

        Не здоровайся, но общайся легко и непринужденно, с лёгким юмором.
        Если один или несколько моих продуктов не годятся для готовки,- интересно обыграй это. 
        Начни сообщение с этого эмодзи:📝

        MESSAGE = {fixed_product_list}
        """

        # Отправляем запрос к OpenAI
        gpt_data = await send_to_openai_async(text=gpt_prompt)

        # Извлекаем ответ от OpenAI
        if gpt_data and "choices" in gpt_data and gpt_data["choices"]:
            response = gpt_data["choices"][0].get("message", {}).get("content", "Не удалось получить ответ.")
        else:
            response = "Не удалось получить ответ от OpenAI."

        # Редактируем сообщение "⏳ Пишу ответ..." на полученный ответ от OpenAI
        await processing_message.edit_text(response, parse_mode="Markdown")

        # Увеличиваем attempts_left после успешной обработки голосового сообщения
        await update_user_attempts(telegram_id)

    except Exception as e:
        logger.error(f"Ошибка при обработке голосового сообщения: {e}")
        await processing_message.edit_text("Произошла ошибка при обработке голосового сообщения. Попробуйте ещё раз позже.")

# Функция для отправки аудио на OpenAI и получения транскрипции
async def transcribe_audio(audio_bytes):
    url = "https://api.openai.com/v1/audio/transcriptions"
    data = aiohttp.FormData()
    data.add_field('file', audio_bytes, filename='audio.ogg', content_type='audio/ogg')
    data.add_field('model', 'whisper-1')

    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers={"Authorization": f"Bearer {OPENAI_API_KEY}"}, data=data) as response:
                if response.status == 200:
                    result = await response.json()
                    return result.get("text")
                else:
                    error_text = await response.text()
                    logger.error(f"Ошибка при транскрипции аудио: {response.status}, {error_text}")
                    return None
    except aiohttp.ClientError as e:
        logger.error(f"Ошибка запроса к OpenAI для транскрипции: {e}")
        return None

# Фоновая задача для обработки запроса и отправки рецепта
async def process_gpt(callback_query: types.CallbackQuery, telegram_id: int, fixed_product_list: str, gpt_prompt: str, last_message_id: str, second_last_message_id: str):
    try:
        # Логируем запрос пользователя
        logger.info(f"Запрос пользователя {telegram_id}: {fixed_product_list}")

        # Отправляем запрос в GPT асинхронно
        gpt_data = await send_to_openai_async(text=gpt_prompt, image_base64=None)

        # Проверка валидности ответа от OpenAI
        if not gpt_data or "choices" not in gpt_data or not gpt_data["choices"]:
            await callback_query.message.edit_text(f"Ошибка анализа продуктов.", parse_mode="Markdown")
            return

        # Обработка ответа от GPT API
        description = gpt_data["choices"][0].get("message", {}).get("content", "Не удалось распознать продукты.")
        logger.info(f"Ответ от OpenAI для пользователя {telegram_id}: успешно получен, длина ответа {len(description)} символов.")

        # Исправляем разметку с помощью fix_markdown
        fixed_text = fix_markdown(description)

        # Логируем полученный рецепт
        logger.info(f"Рецепт для пользователя {telegram_id}: {fixed_text}")

        # Отправляем описание и рецепт как ответ на текущее сообщение
        msg = await callback_query.message.reply(
            text=f"{fixed_text}",
            parse_mode="Markdown"
        )

        # Сохраняем новое сообщение как последнее и обновляем предпоследнее
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'last_message_id': msg.message_id,
            'second_last_message_id': last_message_id,
            'last_message_type': 'recipe'
        })

        # Обновление количества оставшихся попыток
        await update_user_attempts(telegram_id)

    except Exception as e:
        logger.error(f"Ошибка при обработке GPT запроса: {e}")
        await callback_query.message.edit_text("Произошла ошибка при обработке запроса.", parse_mode="Markdown")

# Фоновая задача для обработки фото и отправки рецепта пользователю и в канал
async def process_photo(message: types.Message, telegram_id: int, processing_message: types.Message):
    try:
        # Получение фото в лучшем качестве
        photo = message.photo[-1]
        file_id = photo.file_id
        file = await bot.get_file(file_id)
        file_path = file.file_path

        # Генерация URL для изображения
        file_url = f"https://api.telegram.org/file/bot{BOT_TOKEN}/{file_path}"

        # Скачиваем изображение асинхронно
        async with aiohttp.ClientSession() as session:
            async with session.get(file_url) as response:
                if response.status != 200:
                    msg = await message.reply("Не удалось загрузить изображение.")
                    await redis.hset(f"user:{telegram_id}:last_message", mapping={
                        'buttons_message_id': msg.message_id,
                        'buttons_message_type': 'other'
                    })
                    logger.info(f"Ошибка загрузки изображения. Сообщение отправлено. ID: {msg.message_id}")
                    return
                image_bytes = await response.read()

        # Сжимаем изображение до 512x512
        resized_image = resize_image(image_bytes)

        # Преобразование сжатого изображения в Base64
        resized_image_base64 = convert_image_to_base64(resized_image)

        # Формирование текста запроса для OpenAI
        prompt_text = """
Если на фото готовое блюдо: дай его название, описание и пошаговый рецепт по шаблону.

Если на фото не блюдо, а продукты: перечисли какие продукты на фото, дай описание блюда которое можно из них приготовить, затем напиши его пошаговый рецепт по шаблону.

Если на фото несъедобные объекты,- интересно это обыграй. 

Не здоровайся, но общайся легко и непринужденно, с лёгким юмором.
Перечень ингредиентов промаркируй жирной точкой (•)
Заголовок каждого нового абзаца напиши *жирным* текстом *одинарными* звёздочками.
Начни сообщение с этого эмодзи:📝, но если фото содержит неприличный или шокирующий контент, то начни сообщение с этого эмодзи: 🔞

Template for formatting:
[List the unprocessed products here if they are in the photo]

*Name of the dish*
[here is the description of the dish]

*Ingredients*

• Ingredient 1
• Ingredient 2

*Cooking*

*Stage name*
Instructions

*Stage name*
Instructions

*Calorie content*
Number of calories per serving (Kcal)

Communicate easily and naturally
"""

        # Отправка запроса в OpenAI API асинхронно
        gpt_data = await send_to_openai_async(text=prompt_text, image_base64=resized_image_base64)

        if not gpt_data:
            await processing_message.edit_text("Ошибка анализа изображения. Иногда так бывает, попробуйте ещё раз.")
            return

        # Обработка ответа от GPT API
        description = gpt_data.get("choices", [{}])[0].get("message", {}).get("content", "Не удалось распознать изображение.")
        logger.info(f"Ответ от OpenAI для пользователя {telegram_id}: успешно получен, длина ответа {len(description)} символов.")
        logger.info(f"Рецепт для пользователя {telegram_id}: {description}")

        # Исправляем разметку с помощью fix_markdown
        fixed_text = fix_markdown(description)

        # Редактируем сообщение "Изучаю ваше изображение..." на сообщение с рецептом
        keyboard = InlineKeyboardMarkup(
            inline_keyboard=[
                [InlineKeyboardButton(text="Получить морковку 🥕", callback_data="show_carrot_menu")]
            ]
        )
        await processing_message.edit_text(f"{fixed_text}", parse_mode="Markdown")
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'last_message_id': processing_message.message_id,
            'last_message_type': 'recipe'
        })
        logger.info(f"Сообщение с рецептом отправлено. ID: {processing_message.message_id}")
        
        # Обновление количества оставшихся попыток
        await update_user_attempts(telegram_id)
        
        # Проверяем, начинается ли ответ от OpenAI с эмодзи 🔞
        is_spoiler = fixed_text.startswith("🔞")

        # Если ответ начинается с эмодзи 🔞, добавляем предупреждение в начало сообщения
        if is_spoiler:
            fixed_text = (
                "❗️ Возможно на фото неприемлемый контент! \n"
                "Просто удалите сообщение, если вы не хотите этого видеть.\n\n"
                + fixed_text
            )

        # Сначала отправляем фото
        channel_id = -1002475626558  # ID вашего канала
        photo_message = await bot.send_photo(
            chat_id=channel_id, 
            photo=photo.file_id, 
            caption=None,  # Без подписи
            has_spoiler=is_spoiler  # Скрываем фото, если текст начинается с 🔞
        )

        # Затем отправляем рецепт в ответ на сообщение с фото
        await bot.send_message(
            chat_id=channel_id,
            text=f"{fixed_text}",
            parse_mode="Markdown",
            reply_to_message_id=photo_message.message_id  # Отправляем рецепт в ответ на фото
        )


        logger.info(f"Сообщение с рецептом опубликовано в канале {channel_id}.")

    except Exception as e:
        logger.error(f"Ошибка при обработке изображения: {e}")
        # Вместо отправки сообщения просто логируем ошибку
        logger.info(f"Ошибка при обработке изображения для пользователя {telegram_id}")

@router.message(F.photo)
async def handle_photo(message: types.Message):
    # Проверяем, если это не личный чат, бот игнорирует сообщение
    if message.chat.type != 'private':
        return

    telegram_id = message.from_user.id
    channel_id = -1002469692233  # ID канала для проверки подписки

    # Проверяем подписку и attempts_left
    is_subscribed = await is_user_subscribed(bot, telegram_id, channel_id)
    attempts_left = await get_user_attempts(telegram_id)

    # Если пользователь не подписан и attempts_left > 4, отправляем сообщение с просьбой подписаться
    if not is_subscribed and attempts_left > 4:
        await message.answer(
            "Пожалуйста, подпишитесь на канал @kulinarka, чтобы продолжить использовать бота."
        )
        return

    # Логируем взаимодействие для статистики
    await log_interaction(telegram_id, 'photo')

    # Проверка количества оставшихся попыток
    if attempts_left < 0:
        msg = await message.answer(
            "Кажется, мне запретили с тобой общаться 🥺\nСвяжись с администратором, если считаешь что это ошибка."
        )
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'last_message_id': msg.message_id,
            'last_message_type': 'other'
        })
        return

    # Отправляем сообщение, что мы получаем изображение в ответ на сообщение пользователя
    processing_message = await message.reply("⏳ Изучаю полученное изображение...")
    logger.info(f"Сообщение 'Изучаю ваше изображение...' отправлено. ID: {processing_message.message_id}")

    # Проверяем, есть ли подпись к фото
    if message.caption:
        logger.info(f"Обнаружена подпись к фото: {message.caption}")
        asyncio.create_task(process_photo_with_caption(message, telegram_id, processing_message))
    else:
        logger.info("Подпись к фото отсутствует.")
        asyncio.create_task(process_photo(message, telegram_id, processing_message))

# Обработчик отправки фото c подписью
async def process_photo_with_caption(message: types.Message, telegram_id: int, processing_message: types.Message):
    try:
        # Получаем изображение
        photo = message.photo[-1]
        file_info = await bot.get_file(photo.file_id)
        file_url = f"https://api.telegram.org/file/bot{BOT_TOKEN}/{file_info.file_path}"

        # Скачиваем изображение
        async with aiohttp.ClientSession() as session:
            async with session.get(file_url) as response:
                if response.status != 200:
                    await processing_message.edit_text("Не удалось загрузить изображение.")
                    return
                image_bytes = await response.read()

        # Сжимаем изображение
        resized_image = resize_image(image_bytes)
        image_base64 = convert_image_to_base64(resized_image)

        # Получаем подпись
        caption = message.caption if message.caption else "Опишите изображение"

        # Формируем промпт для OpenAI
        gpt_prompt = f"""
        Если на фото объекты связанные с кулинарией: изучи фото, прочти [MESSAGE] и дай подробный, развёрнутый, профессиональный ответ.

        Если на фото несъедобные объекты,- интересно это обыграй с лёгким юмором.

        Если твой ответ предполагает рецепт приготовления, то используй этот шаблон:
        *Name of the dish*
        "[here is the description of the dish]

        *Ingredients*

        • Ingredient 1
        • Ingredient 2

        *Cooking*

        *Stage name*
        Instructions

        *Stage name*
        Instructions

        *Calorie content*
        Number of calories per serving (Kcal)"

        Пиши на русском
        
        Не здоровайся, но общайся легко и непринужденно.

        Не отвечай на вопросы не связанные с кулинарией.

        Начни сообщение с этого эмодзи:📝, но если фото содержит неприличный или шокирующий контент, то начни сообщение с этого эмодзи: 🔞

        [MESSAGE] = {caption}
        """

        # Отправляем запрос в OpenAI
        gpt_data = await send_to_openai_async(text=gpt_prompt, image_base64=image_base64)

        if not gpt_data:
            await processing_message.edit_text("Ошибка анализа изображения. Иногда так бывает, попробуйте ещё раз.")
            return

        # Обработка ответа от GPT API
        description = gpt_data.get("choices", [{}])[0].get("message", {}).get("content", "Не удалось распознать изображение.")
        logger.info(f"Ответ от OpenAI для пользователя {telegram_id}: успешно получен, длина ответа {len(description)} символов.")
        logger.info(f"Рецепт для пользователя {telegram_id}: {description}")

        # Исправляем разметку с помощью fix_markdown
        fixed_text = fix_markdown(description)

        # Редактируем сообщение "Изучаю ваше изображение..." на сообщение с рецептом
        await processing_message.edit_text(f"{fixed_text}", parse_mode="Markdown")
        await redis.hset(f"user:{telegram_id}:last_message", mapping={
            'last_message_id': processing_message.message_id,
            'last_message_type': 'recipe'
        })

        await update_user_attempts(telegram_id)

        # Проверяем, начинается ли ответ от OpenAI с эмодзи 🔞
        is_spoiler = fixed_text.startswith("🔞")

        # Отправка в канал
        channel_id = -1002475626558  # ID вашего канала

        # 📸 Отправляем фото с подписью
        photo_message = await bot.send_photo(
            chat_id=channel_id,
            photo=photo.file_id,
            caption=caption,  # Используем подпись пользователя
            has_spoiler=is_spoiler  # Скрываем фото, если текст начинается с 🔞
        )

        # 💬 Отправляем рецепт в ответ на фото
        await bot.send_message(
            chat_id=channel_id,
            text=f"{fixed_text}",
            parse_mode="Markdown",
            reply_to_message_id=photo_message.message_id  # Привязываем сообщение к фото
        )

        logger.info(f"Сообщение с рецептом опубликовано в канале {channel_id}.")

    except Exception as e:
        logger.error(f"Ошибка при обработке изображения с подписью: {e}")
        await processing_message.edit_text("Произошла ошибка при обработке изображения. Попробуйте ещё раз позже.")

# Обработчик для всех остальных сообщений
@router.message()
async def handle_unrecognized_message(message: types.Message):
    pass  # Ничего не делаем

# Функция для запуска бота
async def main():
    global db_pool
    global redis
    db_pool = await create_db_pool()  # Инициализируем пул соединений с базой данных
    await create_redis_pool()  # Инициализируем подключение к Redis
    await dp.start_polling(bot)


if __name__ == "__main__":
    asyncio.run(main())
