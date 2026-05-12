package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AccountContextFileTreeDTO {
    @JsonAlias("account_id")
    private String accountId;
    private List<AccountContextFileDTO> files = new ArrayList<>();
}
