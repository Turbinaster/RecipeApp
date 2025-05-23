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
            "Ты — профессиональный кулинарный эксперт. Твоя задача — определить, что на изображении. "
            "Если на изображении готовое блюдо, укажи его название, краткое описание и дай рецепт приготовления. "
            "Если на изображении продукты, перечисли их, предложи блюдо, которое можно из них приготовить, и дай рецепт. "
            "Если на изображении несъедобные объекты, напиши с юмором, что это не еда, и предложи, что можно приготовить вместо этого. "
            "Если есть подпись, учти её для более точного ответа. "
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

# Обработчик запросов
async def handle_image(request):
    try:
        logger.info(f"Received request from {request.remote}")
        reader = await request.multipart()
        
        image_data = None
        caption = None
        
        # Читаем все поля multipart
        while True:
            field = await reader.next()
            if field is None:
                break
            if field.name == "image":
                image_data = await field.read()
                logger.info(f"Received image of size {len(image_data)} bytes")
            elif field.name == "caption":
                caption = (await field.read()).decode("utf-8")
                logger.info(f"Received caption: {caption if caption else 'None'}")
        
        if not image_data:
            logger.warning("No image provided in the request")
            return web.json_response({"error": "No image provided"}, status=400)

        # Сжатие изображения
        compressed_image = await compress_image(image_data)

        # Получение пула PostgreSQL
        db_pool = await get_db_pool()

        # Отправка в OpenAI
        response_text = await analyze_image_with_openai(request, compressed_image, caption, db_pool)
        logger.info("Received response from OpenAI")

        return web.Response(
            text=response_text,
            content_type="text/plain",
            charset="utf-8"
        )
    except Exception as e:
        logger.error(f"Error handling request: {e}")
        return web.json_response({"error": str(e)}, status=500)
    finally:
        if 'db_pool' in locals():
            await db_pool.close()

# Настройка сервера
app = web.Application(client_max_size=10*1024*1024)  # Лимит 10 МБ
app.router.add_post("/upload", handle_image)

# Установка обработчиков для завершения пулов
async def on_shutdown(app):
    if hasattr(app, 'db_pool'):
        await app.db_pool.close()

app.on_shutdown.append(on_shutdown)

if __name__ == "__main__":
    logger.info("Starting server on 46.17.98.58:8080...")
    web.run_app(app, host="127.0.0.1", port=8080)