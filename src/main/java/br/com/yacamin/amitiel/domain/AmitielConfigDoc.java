package br.com.yacamin.amitiel.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "trading_config")
public class AmitielConfigDoc {

    @Id
    private String id;
    private String module;

    private boolean autoSnapshotEnabled;
    private List<String> autoSnapshotWindows;
}
