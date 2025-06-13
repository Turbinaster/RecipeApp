import asyncio
from config import logger
from db import save_daily_recipe
from openai_utils import fetch_daily_recipe  # Добавлен импорт

async def schedule_daily_recipe_update(app):
    # Даем серверу время на запуск перед первым обновлением
    await asyncio.sleep(10)
    
    while True:
        try:
            logger.info("Running scheduled daily recipe update...")
            recipe_text = await fetch_daily_recipe(app['http_session'])
            if recipe_text:
                await save_daily_recipe(app['db_pool'], recipe_text)
                logger.info("Daily recipe saved to database")
            else:
                logger.warning("Failed to fetch daily recipe")
                
            logger.info("Scheduled daily recipe update completed, waiting 24 hours...")
        except Exception as e:
            logger.error(f"Error in scheduled recipe update: {e}")
        await asyncio.sleep(24 * 60 * 60)  # 24 часа