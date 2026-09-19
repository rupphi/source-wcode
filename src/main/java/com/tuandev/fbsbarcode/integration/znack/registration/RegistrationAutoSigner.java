package com.tuandev.fbsbarcode.integration.znack.registration;

/** Called only with a freshly verified GTIN/owner publication response. */
final class RegistrationAutoSigner {
    private RegistrationAutoSigner() {}

    static boolean signIfReady(RegistrationPublication publication,
            ZnackNationalCatalogService catalog, String token, String gtin, Runnable checkIdentity) throws Exception {
        if (publication.published() || !publication.needsSignature()) return false;
        catalog.sign(token, gtin, publication.goodId(), checkIdentity);
        return true;
    }
}
