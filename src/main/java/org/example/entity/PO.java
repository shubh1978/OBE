package org.example.entity;

import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String code; // PO1, PO2


    @Column(length = 2000)
    private String description;


    @ManyToOne
    private Program program;
}