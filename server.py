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

# Инициализация пула подключений к PostgreSQL
async def get_db_pool():
    return await asyncpg.create_pool(
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME,
        host=DB_HOST,
        min_size=5,
        max_size=20
    )

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
        image.save(output, format="JPEG", quality=85)
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
            "Ты — профессиональный кулинарный эксперт. Твоя задача — на основе текста запроса определить, что хочет приготовить пользователь, и дать подробный пошаговый рецепт. "
            "Если запрос содержит продукт или набор продуктов: предложи подходящее блюдо, укажи его название, краткое описание, ингредиенты и подробный рецепт. "
            "Если запрос содержит название блюда: укажи его название, краткое описание, ингредиенты и подробный рецепт. "
            "Если запрос — любой вопрос, связанный с кулинарией: ответь на него, предложив рецепт, если это уместно. Не отвечай на вопросы, не связанные с кулинарией. "
            "Верни ответ строго в формате JSON следующей структуры:\n\n"
            "{\n"
            '  "title": "Название блюда",\n'
            '  "intro": "Описание блюда",\n'
            '  "ingredients": "Ингредиенты в виде списка маркированного жирной точкой • Каждый с новой строки. Для переноса строк используй \\n",\n'
            '  "recipe": "Пошаговый рецепт приготовления с переносами строк через \\n",\n'
            '  "proteins": количество белков на 100 г блюда (в граммах, только число),\n'
            '  "fats": количество жиров на 100 г блюда (в граммах, только число),\n'
            '  "carbs": количество углеводов на 100 г блюда (в граммах, только число),\n'
            '  "calories": калорийность 100 г блюда (в Ккал, только число)\n'
            "}\n\n"
            "Важно: ВЕСЬ ответ должен строго соответствовать указанной JSON-структуре.\n"
            "Ответ ДОЛЖЕН начинаться с символа { и быть корректным JSON-объектом.\n"
            "Общайся с лёгким юмором, но не добавляй никаких пояснений, приветствий, комментариев или форматирования вне JSON.\n"
            "Не используй знак решетки (#) для заголовков.\n\n"
            f"Запрос: {transcription}"
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
            "Ты — профессиональный кулинарный эксперт. Твоя задача — определить, что изображено на фотографии и дать подробный пошаговый рецепт. "
            "Если на фото готовое блюдо: укажи его название, краткое описание, ингредиенты и подробный рецепт. Верни ответ строго в формате JSON следующей структуры:\n\n"
            "{\n"
            '  "title": "Название блюда",\n'
            '  "intro": "Описание блюда",\n'
            '  "ingredients": "Ингредиенты в виде списка маркированного жирной точкой • Каждый с новой строки. Для переноса строк используй \\n",\n'
            '  "recipe": "Пошаговый рецепт приготовления с переносами строк через \\n",\n'
            '  "proteins": количество белков на 100 г блюда (в граммах, только число),\n'
            '  "fats": количество жиров на 100 г блюда (в граммах, только число),\n'
            '  "carbs": количество углеводов на 100 г блюда (в граммах, только число),\n'
            '  "calories": калорийность 100 г блюда (в Ккал, только число)\n'
            "}\n\n"
            "Если изображение содержит только продукты — перечисли их, предложи возможное блюдо и верни ответ строго в формате JSON той же структуры. "
            "Если на изображении несъедобные объекты — пошути, но всё равно верни корректный JSON с вымышленным блюдом. "
            "Если есть подпись, учти её для более точного ответа.\n\n"
            "Важно: ВЕСЬ ответ должен строго соответствовать указанной JSON-структуре.\n"
            "Общайся с лёгким юмором, но не добавляй никаких пояснений, приветствий, комментариев или форматирования вне JSON.\n"
            "Ответ ДОЛЖЕН начинаться с символа { и быть корректным JSON-объектом.\n"
            "Не используй знак решетки (#) для заголовков.\n\n"
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
        logger.error(f"Error in OpenAI request: {e}")
        return f"Ошибка: {str(e)}"

# Обработчик аудио
async def handle_audio(request):
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

        # Сохраняем файл для отладки
        temp_path = f"temp_{audio_filename or 'audio.m4a'}"
        with open(temp_path, "wb") as f:
            f.write(audio_data)
        logger.info(f"Saved audio to {temp_path}")

        # Транскрипция аудио
        transcription = await transcribe_audio(audio_data, content_type="audio/m4a", filename=audio_filename or "audio.m4a")
        if not transcription:
            logger.error("Failed to transcribe audio")
            os.remove(temp_path)
            return web.json_response({"error": "Failed to transcribe audio"}, status=500)

        # Получение пула соединений
        db_pool = await get_db_pool()

        # Получение рецепта от OpenAI
        response_data = await analyze_with_openai(request, transcription, db_pool)
        logger.info(f"Final server response: {response_data}")

        os.remove(temp_path)
        return web.json_response(response_data)
    except Exception as e:
        logger.error(f"Error handling audio request: {e}")
        if 'temp_path' in locals():
            os.remove(temp_path)
        return web.json_response({"error": str(e)}, status=500)
    finally:
        if 'db_pool' in locals():
            await db_pool.close()

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

        # Сжатие изображения
        compressed_image = await compress_image(image_data)

        # Получение пула соединений
        db_pool = await get_db_pool()

        # Отправка в OpenAI
        response_text = await analyze_image_with_openai(request, compressed_image, caption, db_pool)
        logger.info(f"Final image response: {response_text}")

        return web.Response(
            text=response_text,
            content_type="text/plain",
            charset="utf-8"
        )
    except Exception as e:
        logger.error(f"Error handling image request: {e}")
        return web.json_response({"error": str(e)}, status=500)
    finally:
        if 'db_pool' in locals():
            await db_pool.close()

# Настройка сервера
app = web.Application(client_max_size=10*1024*1024)  # Лимит 10 МБ
app.router.add_post("/upload", handle_image)
app.router.add_post("/upload_audio", handle_audio)

async def on_shutdown(app):
    if hasattr(app, 'db_pool'):
        await app.db_pool.close()

app.on_shutdown.append(on_shutdown)

if __name__ == "__main__":
    logger.info("Starting server on 127.0.0.1:8080...")
    web.run_app(app, host="127.0.0.1", port=8080)