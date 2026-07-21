-- TCP task support: protocol discriminator + tcp connect outcome on call records.
-- API rows leave these null; TCP rows set protocol='TCP', tcp_target, tcp_ok.

alter table la_scheduler_task_call add column if not exists protocol varchar(8);
alter table la_scheduler_task_call add column if not exists tcp_target varchar(256);
alter table la_scheduler_task_call add column if not exists tcp_ok boolean;
