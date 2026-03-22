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
@Document(collection = "market_group")
public class MarketGroup {

    @Id
    private String id;

    private String slugPrefix;
    private String displayName;
    private String blockDuration;
    private String binanceStream;
    private boolean active;
}
