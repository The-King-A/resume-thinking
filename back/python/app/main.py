from fastapi import BackgroundTasks, FastAPI

from .analysis_service import analyze_job
from .callback_client import CallbackClient
from .models import AnalysisJob

app = FastAPI()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/internal/v1/analysis-jobs", status_code=202)
async def submit_analysis_job(job: AnalysisJob, background_tasks: BackgroundTasks) -> dict[str, str]:
    async def process() -> None:
        callback = await analyze_job(job)
        await CallbackClient().post(job.callback_url, callback)

    background_tasks.add_task(process)
    return {"status": "accepted", "taskId": str(job.task_id)}
