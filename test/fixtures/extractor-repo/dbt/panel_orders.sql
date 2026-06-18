select
  panels.id,
  orders.id as order_id
from {{ ref('panels') }} as panels
join {{ source('billing', 'orders') }} as orders
  on orders.panel_id = panels.id
