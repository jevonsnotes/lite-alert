alter table la_user add column permissions_json longtext null;

create table la_notify_delivery (
  id varchar(64) primary key,
  trace_id varchar(128) null,
  topic_id varchar(64) not null,
  target_id varchar(64) not null,
  channel varchar(32) not null,
  payload_json longtext not null,
  status varchar(32) not null,
  attempt int not null default 0,
  max_attempts int not null default 5,
  next_retry_at timestamp not null,
  locked_by varchar(128) null,
  locked_at timestamp null,
  last_error longtext null,
  created_at timestamp not null,
  updated_at timestamp not null,
  finished_at timestamp null
);

create index idx_notify_delivery_due on la_notify_delivery(status, next_retry_at);
create index idx_notify_delivery_lock on la_notify_delivery(status, locked_at);
create index idx_notify_delivery_trace on la_notify_delivery(trace_id);
create index idx_notify_delivery_topic on la_notify_delivery(topic_id);
create index idx_notify_delivery_target on la_notify_delivery(target_id);
create index idx_notify_delivery_finished on la_notify_delivery(finished_at);
