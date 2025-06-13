import io
from PIL import Image
from config import logger

def compress_image(file_data):  # Убрана async
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