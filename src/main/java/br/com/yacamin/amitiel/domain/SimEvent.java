package br.com.yacamin.amitiel.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sim_events")
public class SimEvent {

    @Id
    private String id;

    private String slug;
    private long timestamp;
    private String type;
    private String algorithm;
    private Object payload;
}
