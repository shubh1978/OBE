package org.example.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@Table(name = "co_pso_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class COPSOMapping {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne
    private CO co;

    @ManyToOne
    private PSO pso;

    private int weight;
}