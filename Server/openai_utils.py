import aiohttp
import base64
from config import logger, OPENAI_API_KEY

async def transcribe_audio(session, audio_data, content_type="audio/m4a", filename="audio.m4a"):
    try:
        logger.info(f"Transcribing audio with OpenAI, content_type={content_type}, filename={filename}")
        url = "https://api.openai.com/v1/audio/transcriptions"
        headers = {
            "Authorization": f"Bearer {OPENAI_API_KEY}"
        }
        data = aiohttp.FormData()
        data.add_field('file', audio_data, filename=filename, content_type=content_type)
        data.add_field('model', 'whisper-1')

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

async def analyze_text_with_openai(session, transcription):
    try:
        logger.info("Sending text request to OpenAI...")
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
            "model": "gpt-4.1",
            "messages": [
                {
                    "role": "user",
                    "content": [{"type": "text", "text": prompt}]
                }
            ],
            "max_tokens": 4096,
            "temperature": 0.7
        }
        
        async with session.post(
            "https://api.openai.com/v1/chat/completions", 
            headers=headers, 
            json=payload
        ) as response:
            if response.status != 200:
                logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                return None
            result = await response.json()
            return result["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error(f"Error in OpenAI request: {e}")
        return None

async def analyze_image_with_openai(session, image_data, caption=None):
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
            "model": "gpt-4.1",
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
        
        async with session.post(
            "https://api.openai.com/v1/chat/completions", 
            headers=headers, 
            json=payload
        ) as response:
            if response.status != 200:
                logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                return None
            result = await response.json()
            return result["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error(f"Error in OpenAI image request: {e}")
        return None

async def fetch_daily_recipe(session):
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
            "model": "gpt-4.1",
            "messages": [
                {
                    "role": "user",
                    "content": [{"type": "text", "text": prompt}]
                }
            ],
            "max_tokens": 4096,
            "temperature": 0.7
        }
        
        async with session.post(
            "https://api.openai.com/v1/chat/completions", 
            headers=headers, 
            json=payload
        ) as response:
            if response.status != 200:
                logger.error(f"OpenAI API error: {response.status} - {await response.text()}")
                return None
            result = await response.json()
            response_text = result["choices"][0]["message"]["content"]
            
            # Очистка ответа от обёртки
            if response_text.startswith("```json\n"):
                response_text = response_text[8:]
            if response_text.endswith("\n```"):
                response_text = response_text[:-4]
            return response_text.strip()
    except Exception as e:
        logger.error(f"Error fetching daily recipe: {e}")
        return None