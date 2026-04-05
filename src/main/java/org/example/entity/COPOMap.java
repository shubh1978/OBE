package org.example.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "co_po_mapping")
public class COPOMap {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne
    private CO co;

    @ManyToOne
    private PO po;

    private int weight;
}