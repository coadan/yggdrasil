from airflow import DAG
from airflow.operators.bash import BashOperator

with DAG("panel_refresh", schedule_interval="0 2 * * *") as dag:
    extract = BashOperator(task_id="extract")
    transform = BashOperator(task_id="transform")
    extract >> transform
