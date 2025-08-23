package com.happy.bada;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardsResponse5(
    SetItem set1,
    SetItem set2,
    SetItem set3,
    SetItem set4,
    SetItem set5
) {}
