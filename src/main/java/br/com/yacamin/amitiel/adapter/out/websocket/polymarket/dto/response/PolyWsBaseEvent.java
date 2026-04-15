package br.com.yacamin.amitiel.adapter.out.websocket.polymarket.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolyWsBaseEvent {

    @JsonProperty("event_type")
    private String eventType;
    private String timestamp;

}
