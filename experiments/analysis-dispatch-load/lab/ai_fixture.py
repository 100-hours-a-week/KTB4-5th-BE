"""실제 AI 런타임(대기열·소비자·결과 저장·콜백)에 분석 단계만 고정 지연으로 바꾼 가짜 AI.

환경변수: LAB_CONSUMERS(소비자 수), LAB_QUEUE(대기열 크기), LAB_DELAY(이미지 1장 분석 초), LAB_CALLBACK_URL.
"""
import asyncio
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from app.config import Settings
from app.runtime import AnalysisRuntime
from app.store import InMemoryAnalysisStore
from app.adapters.callback import CallbackDelivery
from app.schemas import AnalysisResult
from app.routers import analyses, health, status
from app.error_handlers import handle_app_error, handle_validation_error
from app.errors import AppError

logging.basicConfig(level=logging.WARNING, format='%(asctime)s %(levelname)s %(name)s %(message)s')
control = {'delay': float(os.getenv('LAB_DELAY', '1.0')), 'started': 0, 'finished': 0}
fixture = {
    'documentType': {'value': 'RECEIPT', 'confidence': 1.0},
    'imageQuality': {'score': 1.0, 'issues': []},
    'items': [{
        'itemId': 'milk-1', 'displayStatus': 'RECOGNIZED',
        'name': {'value': '우유', 'source': 'OCR', 'confidence': 1.0},
        'category': {'value': 'DAIRY', 'source': 'BUSINESS_RULE', 'confidence': 1.0},
        'storageType': 'REFRIGERATED', 'measureType': 'COUNT',
        'quantity': {'value': 1, 'source': 'OCR', 'confidence': 1.0},
        'weight': {'value': None, 'unit': 'NONE', 'source': 'BUSINESS_RULE', 'confidence': 1.0},
        'expiration': {'date': '2026-10-23', 'dateType': 'USE_BY'}, 'reviewReasons': []
    }],
    'modelTrace': {'pipelineVersion': 'lab-fixture', 'ocrVersion': 'fixture',
                   'llmModel': 'fixture', 'classifierVersion': 'fixture', 'normalizerVersion': 'fixture', 'policyVersion': 'fixture'}
}


class FixturePipeline:
    async def run(self, record, set_stage):
        control['started'] += 1
        await asyncio.sleep(control['delay'])
        control['finished'] += 1
        return AnalysisResult.model_validate(fixture)

    async def readiness(self):
        return {'fixture': True}

    async def close(self):
        pass


settings = Settings(_env_file=None, app_env='test', internal_api_key='sandbox-ai-key',
                    analysis_queue_maxsize=int(os.getenv('LAB_QUEUE', '100')),
                    analysis_consumer_count=int(os.getenv('LAB_CONSUMERS', '2')),
                    analysis_result_ttl_seconds=3600, analysis_result_max_entries=100000,
                    callback_url=os.getenv('LAB_CALLBACK_URL', 'http://127.0.0.1:38080/api/v1/internal/image-analyses/callback'),
                    callback_api_key='sandbox-ai-key')
store = InMemoryAnalysisStore(ttl_seconds=3600, max_entries=100000)
runtime = AnalysisRuntime(settings, store, FixturePipeline(), CallbackDelivery(settings))


@asynccontextmanager
async def lifespan(app):
    await runtime.start()
    yield
    await runtime.stop()

app = FastAPI(lifespan=lifespan)
app.state.settings = settings
app.state.runtime = runtime
app.add_exception_handler(AppError, handle_app_error)
app.add_exception_handler(RequestValidationError, handle_validation_error)
for router in (analyses.router, health.router, status.router):
    app.include_router(router)


@app.get('/lab/state')
async def state():
    return {'queueSize': runtime.queue.qsize(), 'active': len(runtime.active_analysis_ids), **control}


@app.post('/lab/control')
async def update(payload: dict):
    control.update(payload)
    return control
