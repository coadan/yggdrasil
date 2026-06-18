from dagster import asset, job, op, schedule

@asset
def panel_asset():
    return []

@op
def load_panel():
    return None

@job
def panel_job():
    load_panel()

@schedule(cron_schedule="0 1 * * *", job=panel_job)
def panel_schedule(_context):
    return {}
