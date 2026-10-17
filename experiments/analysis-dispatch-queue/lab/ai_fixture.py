"""Real AI queue/store/runtime/callback; only OCR/LLM pipeline is a deterministic fixture."""
import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

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

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s %(message)s')
root = Path(__file__).resolve().parents[1]
out = Path(os.environ.get('LAB_OUT', str(root / 'evidence')))
control = {'delay': 0.3}
runs = []
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
        entry = {'analysisId': record.analysis_id, 'requestId': record.request.request_id,
                 'objectKey': record.request.image.object_key, 'started': time.time()}
        runs.append(entry)
        with (out / 'ai-executions.jsonl').open('a') as f:
            f.write(json.dumps(entry, ensure_ascii=False) + '\n')
        await asyncio.sleep(control['delay'])
        entry['finished'] = time.time()
        return AnalysisResult.model_validate(fixture)

    async def readiness(self):
        return {'fixture': True}

    async def close(self):
        pass

settings = Settings(_env_file=None, app_env='test', internal_api_key='sandbox-ai-key',
                    analysis_queue_maxsize=int(os.getenv('LAB_QUEUE', '4')),
                    analysis_consumer_count=int(os.getenv('LAB_CONSUMERS', '1')),
                    analysis_result_ttl_seconds=60, callback_max_attempts=3,
                    callback_timeout_seconds=0.5,
                    callback_url='http://127.0.0.1:38082/api/v1/internal/image-analyses/callback',
                    callback_api_key='sandbox-ai-key')
store = InMemoryAnalysisStore(ttl_seconds=60, max_entries=1000)
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
    return {'pid': os.getpid(), 'queueSize': runtime.queue.qsize(), 'capacity': runtime.queue.maxsize,
            'active': sorted(runtime.active_analysis_ids), 'runs': runs,
            'records': [record.to_view().model_dump(by_alias=True, mode='json')
                        for record in list(store._records.values())]}

@app.post('/lab/control')
async def update(payload: dict):
    control.update(payload)
    return control
