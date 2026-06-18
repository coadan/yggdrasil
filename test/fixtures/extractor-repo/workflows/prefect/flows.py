from prefect import flow, task

@task
def extract():
    return []

@flow
def refresh_panels():
    extract()
