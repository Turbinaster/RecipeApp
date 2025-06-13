import aiohttp
import asyncio
import os
from aiohttp import web
from config import logger
from db import init_db_pool, create_tables, get_latest_daily_recipe
from image_utils import compress_image
from openai_utils import transcribe_audio, analyze_text_with_openai, analyze_image_with_openai, fetch_daily_recipe
from scheduler import schedule_daily_recipe_update

# Обработчик для получения рецепта дня
async def handle_daily_recipe(request):
    try:
        logger.info(f"Received daily recipe request from {request.remote}")
        
        # Пропускаем multipart данные без чтения в память
        reader = await request.multipart()
        while True:
            field = await reader.next()
            if field is None:
                break
            # Пропускаем поле без чтения содержимого
            await field.release()
        
        recipe = await get_latest_daily_recipe(request.app['db_pool'])
        if recipe:
            logger.info("Returning daily recipe from database")
            return web.json_response({"recipe": recipe})
        else:
            logger.warning("No daily recipe found, fetching new one")
            recipe_text = await fetch_daily_recipe(request.app['http_session'])
            if recipe_text:
                await save_daily_recipe(request.app['db_pool'], recipe_text)
                logger.info("Returning newly fetched recipe")
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

        response_text = await analyze_text_with_openai(request.app['http_session'], text_data)
        if not response_text:
            return web.json_response({"error": "OpenAI request failed"}, status=500)
            
        return web.json_response({
            "transcription": text_data, 
            "recipe": response_text
        })
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
                logger.info(f"Received audio file: {audio_filename}, size: {len(audio_data)} bytes")

        if not audio_data:
            logger.warning("No audio provided in the request")
            return web.json_response({"error": "No audio provided"}, status=400)

        transcription = await transcribe_audio(
            request.app['http_session'],
            audio_data, 
            content_type="audio/m4a", 
            filename=audio_filename or "audio.m4a"
        )
        if not transcription:
            logger.error("Failed to transcribe audio")
            return web.json_response({"error": "Failed to transcribe audio"}, status=500)

        response_text = await analyze_text_with_openai(request.app['http_session'], transcription)
        if not response_text:
            return web.json_response({"error": "OpenAI request failed"}, status=500)
            
        return web.json_response({
            "transcription": transcription, 
            "recipe": response_text
        })
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

        # Выполняем в отдельном потоке для избежания блокировки
        compressed_image = await asyncio.to_thread(compress_image, image_data)
        response_text = await analyze_image_with_openai(request.app['http_session'], compressed_image, caption)
        if not response_text:
            return web.json_response({"error": "OpenAI request failed"}, status=500)
            
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

# Инициализация приложения
async def init_app():
    app = web.Application(
        client_max_size=10*1024*1024,  # Лимит 10 МБ
        middlewares=[error_middleware]
    )
    
    # Инициализация пула БД
    app['db_pool'] = await init_db_pool()
    await create_tables(app['db_pool'])
    
    # Создаем HTTP-сессию для повторного использования
    connector = aiohttp.TCPConnector(limit=100)  # Увеличиваем лимит соединений
    app['http_session'] = aiohttp.ClientSession(connector=connector)
    
    # Запуск задачи обновления рецепта
    asyncio.create_task(schedule_daily_recipe_update(app))
    logger.info("Scheduled daily recipe update task started")
    
    # Роутинг
    app.router.add_post("/upload", handle_image)
    app.router.add_post("/upload_audio", handle_audio)
    app.router.add_post("/upload_text", handle_text)
    app.router.add_post("/upload_daily_recipe", handle_daily_recipe)
    
    # Обработчик закрытия сессии при остановке
    async def close_session(app):
        await app['http_session'].close()
    app.on_cleanup.append(close_session)
    
    return app

# Запуск сервера
if __name__ == "__main__":
    logger.info("Starting server on 127.0.0.1:8080...")
    web.run_app(init_app(), host="127.0.0.1", port=8080)