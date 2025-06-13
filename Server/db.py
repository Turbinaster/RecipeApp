import asyncpg
from config import logger, DB_USER, DB_PASSWORD, DB_NAME, DB_HOST

async def init_db_pool():
    logger.info("Initializing database pool...")
    pool = await asyncpg.create_pool(
        user=DB_USER,
        password=DB_PASSWORD,
        database=DB_NAME,
        host=DB_HOST,
        min_size=5,
        max_size=100,  # Увеличен размер пула
        command_timeout=60  # Таймаут для операций
    )
    logger.info("Database pool initialized")
    return pool

async def create_tables(pool):
    async with pool.acquire() as connection:
        await connection.execute("""
            CREATE TABLE IF NOT EXISTS daily_recipe (
                id SERIAL PRIMARY KEY,
                recipe_text TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        logger.info("Checked/created all database tables")

async def save_daily_recipe(pool, recipe_text):
    async with pool.acquire() as connection:
        await connection.execute("DELETE FROM daily_recipe")
        await connection.execute(
            "INSERT INTO daily_recipe (recipe_text) VALUES ($1)",
            recipe_text
        )

async def get_latest_daily_recipe(pool):
    async with pool.acquire() as connection:
        return await connection.fetchval(
            "SELECT recipe_text FROM daily_recipe ORDER BY created_at DESC LIMIT 1"
        )