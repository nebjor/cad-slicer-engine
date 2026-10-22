package com.slicercore.cart;

import com.slicercore.order.RetentionSweep;
import org.springframework.stereotype.Component;

/**
 * Joins the nightly retention run: cart lines unchanged for {@code account.cart-retention-days}
 * are deleted with their model files. A customer who comes back after that starts a new cart.
 */
@Component
public class CartRetentionSweep implements RetentionSweep {

    private final AccountCartService carts;

    public CartRetentionSweep(AccountCartService carts) {
        this.carts = carts;
    }

    @Override
    public String subject() {
        return "stale cart lines";
    }

    @Override
    public int sweep() {
        return carts.deleteStale();
    }
}
