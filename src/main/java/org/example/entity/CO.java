package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String code; // CO1, CO2


    @Column(length = 2000)
    private String description;


    @ManyToOne
    private Course course;
}