package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

final class OwnershipDiagnosticProbeTest {
    @Test
    void printExactOwnershipMessages() {
        StringBuilder out = new StringBuilder();
        out.append("MUT_LOCAL=").append(message("""
                fnc bad() => void {
                  struct Local {
                    pub let value: int;
                    pub bump(self &mut self)() => void {
                      self.value = self.value + 1;
                      return;
                    }
                  }
                  val Local item = Local { value = 0 };
                  item.bump();
                  return;
                }
                """)).append("\n");

        out.append("IMM_FIELD=").append(message("""
                fnc bad() => void {
                  struct Local {
                    pub val value: int;
                  }
                  let Local item = Local { value = 0 };
                  item.value = 1;
                  return;
                }
                """)).append("\n");

        out.append("ALIAS_MUT=").append(message("""
                fnc bad() => void {
                  struct Local {
                    pub let value: int;
                    pub bump(self &mut self)() => void {
                      self.value = self.value + 1;
                      return;
                    }
                  }
                  type Alias = Local;
                  val Alias item = Local { value = 0 };
                  item.bump();
                  return;
                }
                """));
        fail(out.toString());
    }

    private static String message(String source) {
        try {
            TypeChecker.check(Parser.parse(source));
            return "<NO ERROR>";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
