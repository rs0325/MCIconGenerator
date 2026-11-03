package io.github.rs0325.mcicon.internal.managed;

import io.github.rs0325.mcicon.McIconException;

/** RuntimeStore・回収・lease生成へ到達する前に確定した、同梱pinsの不正。 */
public final class BundledPinsFailure extends RuntimeException {
    private BundledPinsFailure(Throwable cause){super(cause);}
    static McIconException wrap(Throwable cause) {
        return new McIconException("MANAGED_RUNTIME_INVALID",cause instanceof McIconException?cause.getMessage():"同梱pinの形式が不正です",1,new BundledPinsFailure(cause));
    }
}
