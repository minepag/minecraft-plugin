package com.minepag.plugin.protocol;

import java.util.OptionalLong;

public sealed interface ApiOutcome<T> {
    record Ok<T>(T value) implements ApiOutcome<T> {}

    /** Resposta HTTP fora de 2xx. {@code code} segue {@code src/contracts/api-errors.ts} quando o corpo é válido. */
    record HttpFailure<T>(int status, String code, String message, OptionalLong retryAfterSeconds)
            implements ApiOutcome<T> {
        public boolean isAuthFailure() {
            return status == 401 || status == 403;
        }

        /** Servidor pausado no painel: não é problema de token; as entregas voltam quando ele for reativado. */
        public boolean isServerPaused() {
            return "server_paused".equals(code);
        }

        public boolean isPluginOutdated() {
            return status == 426;
        }

        public boolean isRateLimited() {
            return status == 429;
        }
    }

    /** Falha de rede, timeout ou resposta 2xx que não segue o contrato. */
    record NetworkFailure<T>(String message) implements ApiOutcome<T> {}
}
