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
@Document(collection = "algorithm_config")
public class AlgorithmConfig {

    @Id
    private String id;

    private String name;
    private String engine;
    private boolean active;
}
