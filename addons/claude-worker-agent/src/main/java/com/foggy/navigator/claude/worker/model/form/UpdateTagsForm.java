package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.List;

@Data
public class UpdateTagsForm {
    private List<String> tags;
}
