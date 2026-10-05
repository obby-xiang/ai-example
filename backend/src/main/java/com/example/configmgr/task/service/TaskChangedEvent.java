package com.example.configmgr.task.service;

import com.example.configmgr.task.entity.Task;
import org.springframework.context.ApplicationEvent;

public class TaskChangedEvent extends ApplicationEvent {
    private final Task task;
    private final String summary;

    public TaskChangedEvent(Object source, Task task, String summary) {
        super(source);
        this.task = task;
        this.summary = summary;
    }

    public Task getTask() { return task; }
    public String getSummary() { return summary; }
}
