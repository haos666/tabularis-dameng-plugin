package dev.tabularis.dameng;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        RpcServer server = new RpcServer(new DamengClient());

        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String response = server.handleLine(trimmed);
                out.write(response);
                out.newLine();
                out.flush();
            }
        }
    }
}
