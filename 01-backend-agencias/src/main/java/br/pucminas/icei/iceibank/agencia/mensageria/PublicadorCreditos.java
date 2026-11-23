package br.pucminas.icei.iceibank.agencia.mensageria;

import br.pucminas.icei.iceibank.agencia.dto.CreditoMensagem;

/** Porta de saida para publicar creditos destinados a outra agencia. */
public interface PublicadorCreditos {

    /**
     * Publica o credito para a agencia de destino.
     *
     * @throws PublicacaoFalhouException se o broker estiver inacessivel (a mensagem NAO foi publicada)
     */
    void publicar(int agenciaDestino, CreditoMensagem mensagem);

    class PublicacaoFalhouException extends RuntimeException {
        public PublicacaoFalhouException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
