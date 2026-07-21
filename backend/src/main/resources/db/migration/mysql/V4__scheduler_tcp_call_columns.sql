-- TCP task support: protocol discriminator + tcp connect outcome on call records.
-- API rows leave these null; TCP rows set protocol='TCP', tcp_target, tcp_ok.

alter table la_scheduler_task_call add column protocol varchar(8) null;
alter table la_scheduler_task_call add column tcp_target varchar(256) null;
alter table la_scheduler_task_call add column tcp_ok tinyint(1) null;
