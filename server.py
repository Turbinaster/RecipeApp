import aiohttp
import asyncio
import base64
import io
import logging
import os
from PIL import Image
from aiohttp import web
from dotenv import load_dotenv
import asyncpg

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[
        logging.FileHandler("server.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Загрузка переменных окружения
load_dotenv()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
DB_USER = os.getenv("DB_USER")
DB_PASSWORD = os.getenv("DB_PASSWORD")
DB_NAME = os.getenv("DB_NAME")
DB_HOST = os.getenv("DB_HOST")

# Проверка переменных окружения
if not OPENAI_API_KEY:
    logger.error("OPENAI_API_KEY is not set in .env file")
    raise ValueError("OPENAI_API_KEY is required")
if not all([DB_USER, DB_PASSWORD, DB_NAME, DB_HOST]):
    logger.error("Database configuration is incomplete in .env file")
    raise ValueError("Database configuration is required")

# Инициализация пула подключений к PostgreSQL
async def init_db_pool(app):
    logger.info("Initializing database pool...")
    app['db_pool'] = await asyncpg.create_pool(
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME,
        host=DB_HOST,
        min_size=5,
        max_size=20
    )
    logger.info("Database pool initialized")
    # Создание таблиц
    async with app['db_pool'].acquire() as connection:
        await connection.execute("""
            CREATE TABLE IF NOT EXISTS request_logs (
                id SERIAL PRIMARY KEY,
                user_ip TEXT,
                image_size INTEGER,
                response_text TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        await connection.execute("""
            CREATE TABLE IF NOT EXISTS daily_recipe (
                id SERIAL PRIMARY KEY,
                recipe_text TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        logger.info("Checked/created all database tables")
    
    # Запуск задачи после инициализации БД
    asyncio.create_task(schedule_daily_recipe_update(app))
    logger.info("Scheduled daily recipe update task started")

# Функция для сжатия изображения
async def compress_image(file_data):
    try:
        logger.info("Checking image size...")
        image = Image.open(io.BytesIO(file_data))
        width, height = image.size
        logger.info(f"Original image size: {width}x{height} pixels")
        
        image.thumbnail((512, 512), Image.Resampling.LANCZOS)
        compressed_width, compressed_height = image.size
        output = io.BytesIO()
        image.save(output, format="JPEG", quality=100)
        compressed_data = output.getvalue()
        compressed_size_mb = len(compressed_data) / (1024 * 1024)
        logger.info(f"Compressed image size: {compressed_width}x{compressed_height} pixels, {compressed_size_mb:.2f} MB")
        
        return compressed_data
    except Exception as e:
        logger.error(f"Error compressing image: {e}")
        raise

# Функция для транскрипции аудио через OpenAI
async def transcribe_audio(audio_data, content_type="audio/m4a", filename="audio.m4a"):
    try:
        logger.info(f"Transcribing audio with OpenAI, content_type={content_type}, filename={filename}")
        url = "https://api.openai.com/v1/audio/transcriptions"
        headers = {
            "Authorization": f"Bearer {OPENAI_API_KEY}"
        }
        data = aiohttp.FormData()
        data.add_field('file', audio_data, filename=filename, content_type=content_type)
        data.add_field('model', 'whisper-1')

        async with aiohttp.ClientSession() as session:
            async with session.post(url, headers=headers, data=data) as response:
                if response.status == 200:
                    result = await response.json()
                    logger.info("Audio transcribed successfully")
                    return result.get("text")
                else:
                    error_text = await response.text()
                    logger.error(f"Transcription error: {response.status}, {error_text}")
                    return None
    except Exception as e:
        logger.error(f"Error in audio transcription: {e}")
        return None

# Функция для отправки запроса в OpenAI
async def analyze_with_openai(request, transcription, db_pool=None):
    try:
        logger.info("Sending request to OpenAI...")
        headers = {
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json"
        }
        prompt = (
            " Ты — профессиональный кулинарный эксперт. Изучи Вопрос и верни ответ строго в формате JSON без обёртки ```json следующей структуры:\n\n"
            "{\n"
            '  "title": "Название блюда или ответа",\n'
            '  "intro": "Ответ на вопрос. Если вопрос не связан с кулинарией то обыграй это с лёгким юмором но не отвечай",\n'
            '  "ingredients": "Если ответ содержит рецепт приготовления блюда, то здесь ингредиенты в виде списка маркированного жирной точкой • , каждый с новой строки, для переноса строк используй \\n. Иначе none",\n'
            '  "recipe": "Если ответ содержит рецепт приготовления блюда, то здесь подробный пошаговый рецепт приготовления с переносами строк через \\n. Иначе none ",\n'
            '  "proteins": количество белков на 100 г блюда (в граммах, только число),\n'
            '  "fats": количество жиров на 100 г блюда (в граммах, только число),\n'
            '  "carbs": количество углеводов на 100 г блюда (в граммах, только число),\n'
            '  "calories": калорийность 100 г блюда (в Ккал, только число)\n'
            "}\n\n"
            "ВАЖНО! ВЕСЬ ответ должен строго соответствовать указанной JSON-структуре, начинаться с символа { и быть корректным JSON-объектом! \n"
            "Не используй знак решетки (#) для заголовков.\n\n"
            f"Вопрос: {transcription}"
        )

        payload = {
            "model": "gpt-4o",
            "messages": [
                {
                    "role": "user",
                    "content": [{"type": "text", "text": prompt}]
                }
            ],
            "max_tokens": 4096,
            "temperature": 0.7
        }
        async with aiohttp.ClientSession() as session:
            async with session.post("https://api.openai.com/v1/chat/completions", headers=headers, json=payload) as response:
                if response.status != 200:
                    logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                    return f"Ошибка OpenAI: {response.status}"
                result = await response.json()
                response_text = result["choices"][0]["message"]["content"]
                logger.info(f"OpenAI response: {response_text}")
                if db_pool:
                    async with db_pool.acquire() as connection:
                        await connection.execute(
                            "INSERT INTO request_logs (user_ip, image_size, response_text) VALUES ($1, $2, $3)",
                            request.remote,
                            len(transcription.encode('utf-8')),
                            response_text
                        )
                return {"transcription": transcription, "recipe": response_text}
    except Exception as e:
        logger.error(f"Error in OpenAI request: {e}")
        return f"Ошибка: {str(e)}"

# Функция для отправки изображения в OpenAI
async def analyze_image_with_openai(request, image_data, caption=None, db_pool=None):
    try:
        logger.info("Sending image to OpenAI...")
        base64_image = base64.b64encode(image_data).decode("utf-8")
        headers = {
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json"
        }
        prompt = (
            " Ты — профессиональный кулинарный эксперт. Верни ответ строго в формате JSON следующей структуры:\n\n"
            "{\n"
            '  "title": "Название блюда",\n'
            '  "intro": "Если на фото неприемлемый контент, то ОБЯЗАТЕЛЬНО тактично уйди от ответа здесь. Если на фото готовое блюдо: дай его краткое интересное описание. Если продукты — перечисли их, предложи возможное блюдо и дай его описание. Если один или несколько объектов на фото несъедобны - обыграй это с лёгким юмором. Если изображение на фото не связано с кулинарией, то не пиши рецепт и ингредиенты.", \n'
            '  "ingredients": "Если ответ содержит рецепт приготовления блюда, то здесь ингредиенты в виде списка маркированного жирной точкой • , каждый с новой строки, для переноса строк используй \\n. Иначе none",\n'
            '  "recipe": "Если ответ содержит рецепт приготовления блюда, то здесь подробный пошаговый рецепт приготовления с переносами строк через \\n. Иначе none ",\n'
            '  "proteins": количество белков на 100 г блюда (в граммах, только число),\n'
            '  "fats": количество жиров на 100 г блюда (в граммах, только число),\n'
            '  "carbs": количество углеводов на 100 г блюда (в граммах, только число),\n'
            '  "calories": калорийность 100 г блюда (в Ккал, только число)\n'
            "}\n\n"
            "ВАЖНО! ВЕСЬ ответ должен строго соответствовать указанной JSON-структуре, начинаться с символа { и быть корректным JSON-объектом! ДАЖЕ ЕСЛИ НА ФОТО НЕПРИЕМЛЕМЫЙ КОНТЕНТ!!!\n"
            "Не используй знак решетки (#) для заголовков.\n\n"
            "Если есть подпись, учти её для более точного ответа.\n\n"
            f"Подпись: {caption if caption else 'Нет подписи'}"
        )

        payload = {
            "model": "gpt-4o",
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"}}
                    ]
                }
            ],
            "max_tokens": 4096,
            "temperature": 0.7
        }
        async with aiohttp.ClientSession() as session:
            async with session.post("https://api.openai.com/v1/chat/completions", headers=headers, json=payload) as response:
                if response.status != 200:
                    logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                    return f"Ошибка OpenAI: {response.status}"
                result = await response.json()
                response_text = result["choices"][0]["message"]["content"]
                logger.info(f"OpenAI image response: {response_text}")
                if db_pool:
                    async with db_pool.acquire() as connection:
                        await connection.execute(
                            "INSERT INTO request_logs (user_ip, image_size, response_text) VALUES ($1, $2, $3)",
                            request.remote,
                            len(image_data),
                            response_text
                        )
                return response_text
    except Exception as e:
        logger.error(f"Error in OpenAI image request: {e}")
        return f"Ошибка: {str(e)}"

# Функция для получения рецепта дня
async def fetch_daily_recipe(db_pool):
    try:
        logger.info("Fetching daily recipe from OpenAI...")
        headers = {
            "Authorization": f"Bearer {OPENAI_API_KEY}",
            "Content-Type": "application/json"
        }
        prompt = (
            " Ты — профессиональный шеф-повар. Выбери любое случайное, максимально рандомное блюдо одной из популярных кухонь мира, кроме топ-10 самых популярных блюд и верни ответ строго в формате JSON следующей структуры:\n\n"
            "{\n"
            '  "title": "Название блюда",\n'
            '  "intro": "Интересное, яркое описание блюда",\n'
            '  "ingredients": "Ингредиенты в виде списка маркированного жирной точкой • , каждый с новой строки, для переноса строк используй \\n ",\n'
            '  "recipe": "Подробный пошаговый рецепт приготовления с переносами строк через \\n ",\n'
            '  "proteins": количество белков на 100 г блюда (в граммах, только число),\n'
            '  "fats": количество жиров на 100 г блюда (в граммах, только число),\n'
            '  "carbs": количество углеводов на 100 г блюда (в граммах, только число),\n'
            '  "calories": калорийность 100 г блюда (в Ккал, только число)\n'
            "}\n\n"
            "ВАЖНО! ВЕСЬ ответ должен строго соответствовать указанной JSON-структуре, начинаться с символа { и быть корректным JSON-объектом! \n"
            "Не используй знак решетки (#) для заголовков.\n\n"
        )

        payload = {
            "model": "gpt-4o",
            "messages": [
                {
                    "role": "user",
                    "content": [{"type": "text", "text": prompt}]
                }
            ],
            "max_tokens": 4096,
            "temperature": 0.7
        }
        async with aiohttp.ClientSession() as session:
            async with session.post("https://api.openai.com/v1/chat/completions", headers=headers, json=payload) as response:
                if response.status != 200:
                    logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                    return None
                result = await response.json()
                response_text = result["choices"][0]["message"]["content"]
                logger.info(f"OpenAI daily recipe response: {response_text}")
                
                # Очистка ответа от обёртки ```json
                if response_text.startswith("```json\n"):
                    response_text = response_text[8:]
                if response_text.endswith("\n```"):
                    response_text = response_text[:-4]
                response_text = response_text.strip()
                
                # Сохранение рецепта в базе данных
                async with db_pool.acquire() as connection:
                    await connection.execute("DELETE FROM daily_recipe")
                    await connection.execute(
                        "INSERT INTO daily_recipe (recipe_text) VALUES ($1)",
                        response_text
                    )
                    logger.info("Daily recipe saved to database")
                return response_text
    except Exception as e:
        logger.error(f"Error fetching daily recipe: {e}")
        return None

# Функция для периодического обновления рецепта дня
async def schedule_daily_recipe_update(app):
    # Даем серверу время на запуск перед первым обновлением
    await asyncio.sleep(10)
    
    while True:
        try:
            logger.info("Running scheduled daily recipe update...")
            await fetch_daily_recipe(app['db_pool'])
            logger.info("Scheduled daily recipe update completed, waiting 24 hours...")
        except Exception as e:
            logger.error(f"Error in scheduled recipe update: {e}")
        await asyncio.sleep(24 * 60 * 60)  # 24 часа

# Обработчик для получения рецепта дня
async def handle_daily_recipe(request):
    try:
        logger.info(f"Received daily recipe request from {request.remote}")
        
        # Обработка multipart данных (если есть)
        reader = await request.multipart()
        while True:
            field = await reader.next()
            if field is None:
                break
            # Пропускаем все поля (ожидаем пустой запрос)
            logger.info(f"Skipping multipart field: {field.name}")
            await field.read()
        
        async with request.app['db_pool'].acquire() as connection:
            recipe = await connection.fetchval("SELECT recipe_text FROM daily_recipe ORDER BY created_at DESC LIMIT 1")
            if recipe:
                logger.info(f"Returning daily recipe from database")
                return web.json_response({"recipe": recipe})
            else:
                logger.warning("No daily recipe found, fetching new one")
                recipe_text = await fetch_daily_recipe(request.app['db_pool'])
                if recipe_text:
                    logger.info(f"Returning newly fetched recipe")
                    return web.json_response({"recipe": recipe_text})
                else:
                    logger.error("Failed to fetch daily recipe")
                    return web.json_response({"error": "Failed to fetch daily recipe"}, status=500)
    except Exception as e:
        logger.error(f"Error handling daily recipe request: {e}")
        return web.json_response({"error": str(e)}, status=500)

# Обработчик текстовых запросов
async def handle_text(request):
    try:
        logger.info(f"Received text request from {request.remote}")
        reader = await request.multipart()
        
        text_data = None
        
        while True:
            field = await reader.next()
            if field is None:
                break
            if field.name == "text":
                text_data = await field.read()
                text_data = text_data.decode("utf-8")
                logger.info(f"Received text: {text_data}")

        if not text_data:
            logger.warning("No text provided in the request")
            return web.json_response({"error": "No text provided"}, status=400)

        response_data = await analyze_with_openai(request, text_data, request.app['db_pool'])
        logger.info(f"Final text response: {response_data}")
        return web.json_response(response_data)
    except Exception as e:
        logger.error(f"Error handling text request: {e}")
        return web.json_response({"error": str(e)}, status=500)

# Обработчик аудио
async def handle_audio(request):
    temp_path = None
    try:
        logger.info(f"Received audio request from {request.remote}")
        reader = await request.multipart()
        
        audio_data = None
        audio_filename = None
        
        while True:
            field = await reader.next()
            if field is None:
                break
            if field.name == "audio":
                audio_data = await field.read()
                audio_filename = field.filename
                content_type = field.headers.get('Content-Type', 'unknown')
                logger.info(f"Received audio file: {audio_filename}, size: {len(audio_data)} bytes, content-type: {content_type}")

        if not audio_data:
            logger.warning("No audio provided in the request")
            return web.json_response({"error": "No audio provided"}, status=400)

        temp_path = f"temp_{audio_filename or 'audio.m4a'}"
        with open(temp_path, "wb") as f:
            f.write(audio_data)
        logger.info(f"Saved audio to {temp_path}")

        transcription = await transcribe_audio(audio_data, content_type="audio/m4a", filename=audio_filename or "audio.m4a")
        if not transcription:
            logger.error("Failed to transcribe audio")
            return web.json_response({"error": "Failed to transcribe audio"}, status=500)

        response_data = await analyze_with_openai(request, transcription, request.app['db_pool'])
        logger.info(f"Final server response: {response_data}")
        return web.json_response(response_data)
    except Exception as e:
        logger.error(f"Error handling audio request: {e}")
        return web.json_response({"error": str(e)}, status=500)
    finally:
        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)
            logger.info(f"Removed temporary file: {temp_path}")

# Обработчик запросов для изображений
async def handle_image(request):
    try:
        logger.info(f"Received image request from {request.remote}")
        reader = await request.multipart()
        
        image_data = None
        caption = None
        
        while True:
            field = await reader.next()
            if field is None:
                break
            if field.name == "image":
                image_data = await field.read()
                logger.info(f"Received image of size {len(image_data)} bytes")
            elif field.name == "caption":
                caption = await field.read()
                caption = caption.decode("utf-8")
                logger.info(f"Received caption: {caption if caption else 'None'}")
        
        if not image_data:
            logger.warning("No image provided in the request")
            return web.json_response({"error": "No image provided"}, status=400)

        compressed_image = await compress_image(image_data)
        response_text = await analyze_image_with_openai(request, compressed_image, caption, request.app['db_pool'])
        logger.info(f"Final image response: {response_text}")
        return web.Response(
            text=response_text,
            content_type="text/plain",
            charset="utf-8"
        )
    except Exception as e:
        logger.error(f"Error handling image request: {e}")
        return web.json_response({"error": str(e)}, status=500)

# Middleware для обработки ошибок
async def error_middleware(app, handler):
    async def middleware_handler(request):
        try:
            return await handler(request)
        except web.HTTPException as ex:
            return web.json_response({"error": ex.reason}, status=ex.status)
        except Exception as e:
            logger.exception(f"Unhandled exception: {e}")
            return web.json_response({"error": "Internal server error"}, status=500)
    return middleware_handler

# Настройка сервера
async def setup_app():
    app = web.Application(client_max_size=10*1024*1024, middlewares=[error_middleware])  # Лимит 10 МБ
    app.router.add_post("/upload", handle_image)
    app.router.add_post("/upload_audio", handle_audio)
    app.router.add_post("/upload_text", handle_text)
    app.router.add_post("/upload_daily_recipe", handle_daily_recipe)
    app.on_startup.append(init_db_pool)
    app.on_shutdown.append(on_shutdown)
    return app

async def on_shutdown(app):
    if 'db_pool' in app:
        await app['db_pool'].close()
        logger.info("Database pool closed")

if __name__ == "__main__":
    logger.info("Starting server on 127.0.0.1:8080...")
    web.run_app(setup_app(), host="127.0.0.1", port=8080)