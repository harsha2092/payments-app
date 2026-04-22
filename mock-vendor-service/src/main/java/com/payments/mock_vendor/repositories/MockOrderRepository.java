package com.payments.mock_vendor.repositories;

import com.payments.mock_vendor.entities.MockOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MockOrderRepository extends JpaRepository<MockOrder, String> {
}
