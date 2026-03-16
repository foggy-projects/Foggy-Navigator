import logging
from typing import Optional

from src.core.config import config
from src.core.key_pool import BackendKey

logger = logging.getLogger(__name__)


class ModelManager:
    def __init__(self, config):
        self.config = config

    def map_claude_model_to_openai(
        self,
        claude_model: str,
        backend_key: Optional[BackendKey] = None,
        has_image: bool = False,
    ) -> str:
        """Map Claude model names to OpenAI model names based on BIG/MIDDLE/SMALL pattern.

        If backend_key is provided, use its per-key model settings; otherwise fall back to global config.
        If has_image=True and the backend has a vision_model configured, use the vision model instead.
        """
        # Vision model override: if request contains images and backend has vision_model
        if has_image and backend_key and backend_key.vision_model:
            logger.info(
                "Image detected → vision model override: %s → %s (backend %s)",
                claude_model, backend_key.vision_model, backend_key.name,
            )
            return backend_key.vision_model

        # If it's already an OpenAI model, return as-is
        if claude_model.startswith("gpt-") or claude_model.startswith("o1-"):
            return claude_model

        # If it's other supported models (ARK/Doubao/DeepSeek), return as-is
        if (claude_model.startswith("ep-") or claude_model.startswith("doubao-") or
            claude_model.startswith("deepseek-")):
            return claude_model

        # Resolve model names from backend_key or global config
        if backend_key:
            big = backend_key.big_model
            middle = backend_key.middle_model
            small = backend_key.small_model
        else:
            big = self.config.big_model
            middle = self.config.middle_model
            small = self.config.small_model

        # Map based on model naming patterns
        model_lower = claude_model.lower()
        if 'haiku' in model_lower:
            return small
        elif 'sonnet' in model_lower:
            return middle
        elif 'opus' in model_lower:
            return big
        else:
            # Default to big model for unknown models
            return big

model_manager = ModelManager(config)
