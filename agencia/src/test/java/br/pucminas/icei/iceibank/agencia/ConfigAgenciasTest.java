package br.pucminas.icei.iceibank.agencia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.pucminas.icei.iceibank.agencia.config.ConfigAgencias;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigAgenciasTest {

    @Test
    @DisplayName("a agencia responsavel e o resto da divisao do id da conta pelo numero de agencias")
    void distribuiContasEntreAsTresAgencias() {
        assertEquals(0, ConfigAgencias.agenciaResponsavel(0));
        assertEquals(1, ConfigAgencias.agenciaResponsavel(1));
        assertEquals(2, ConfigAgencias.agenciaResponsavel(2));
        assertEquals(0, ConfigAgencias.agenciaResponsavel(3));
        assertEquals(1, ConfigAgencias.agenciaResponsavel(31));
    }

    @Test
    @DisplayName("cada agencia tem uma url propria derivada da porta base")
    void portasDerivadasDoOffsetPessoal() {
        assertEquals(3, ConfigAgencias.NUMERO_AGENCIAS);
        assertEquals(4000 + ConfigAgencias.OFFSET, ConfigAgencias.PORTA_BASE);
        assertEquals("http://localhost:" + ConfigAgencias.PORTA_BASE, ConfigAgencias.urlDaAgencia(0));
        assertEquals("http://localhost:" + (ConfigAgencias.PORTA_BASE + 2), ConfigAgencias.urlDaAgencia(2));
    }

    @Test
    @DisplayName("a porta de uma agencia e a porta base somada ao id dela")
    void portaDeCadaAgencia() {
        assertEquals(ConfigAgencias.PORTA_BASE, ConfigAgencias.portaDaAgencia(0));
        assertEquals(ConfigAgencias.PORTA_BASE + 1, ConfigAgencias.portaDaAgencia(1));
    }
}
