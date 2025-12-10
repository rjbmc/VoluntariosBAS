// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Servlet para exportar el resumen de una campa�a a un fichero CSV.
 * Solo accesible para administradores.
 */
@WebServlet("/exportar-resumen")
public class ExportarResumenServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(ExportarResumenServlet.class);

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        if (campanaId == null || campanaId.trim().isEmpty()) {
            try {
                campanaId = getActiveCampaign();
                if (campanaId == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campa�a activa para exportar.");
                    return;
                }
            } catch (SQLException e) {
                logger.error("Error al buscar la campaña activa para exportar resumen", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al buscar la campa�a activa.");
                return;
            }
        }

        // --- Configurar la respuesta para forzar la descarga de un fichero CSV ---
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"resumen_" + campanaId + ".csv\"");

        try (Connection conn = DatabaseUtil.getConnection();
             PrintWriter out = response.getWriter()) {

            // --- CAMBIO: Cabecera del CSV actualizada con m�s detalles ---
            out.println("Codigo Tienda;Denominacion;Huecos T1;Voluntarios T1;Acompanantes T1;Total Ocup. T1;% Ocup. T1;Huecos T2;Voluntarios T2;Acompanantes T2;Total Ocup. T2;% Ocup. T2;Huecos T3;Voluntarios T3;Acompanantes T3;Total Ocup. T3;% Ocup. T3;Huecos T4;Voluntarios T4;Acompanantes T4;Total Ocup. T4;% Ocup. T4");

            Map<Integer, TurnoStats[]> statsPorTienda = getStatsPorTienda(conn, campanaId);

            String sql = "SELECT codigo, denominacion, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE disponible = 'S' ORDER BY denominacion";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    int tiendaId = rs.getInt("codigo");
                    TurnoStats[] stats = statsPorTienda.getOrDefault(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});
                    
                    StringBuilder line = new StringBuilder();
                    line.append(tiendaId).append(";");
                    line.append(escapeCsv(rs.getString("denominacion"))).append(";");

                    // --- CAMBIO: Calcular y a�adir los nuevos campos para cada turno ---
                    for (int i = 1; i <= 4; i++) {
                        int huecos = rs.getInt("HuecosTurno" + i);
                        int voluntarios = stats[i-1].voluntarios;
                        int acompanantes = stats[i-1].acompanantes;
                        int totalOcupacion = voluntarios + acompanantes;
                        double porcentajeOcupacion = (huecos > 0) ? (100.0 * totalOcupacion / huecos) : (totalOcupacion > 0 ? 100.0 : 0.0);

                        line.append(huecos).append(";");
                        line.append(voluntarios).append(";");
                        line.append(acompanantes).append(";");
                        line.append(totalOcupacion).append(";");
                        line.append(String.format("%.1f%%", porcentajeOcupacion)).append(i == 4 ? "" : ";");
                    }
                    out.println(line.toString());
                }
            }
        } catch (SQLException e) {
            logger.error("Error de SQL al generar CSV de resumen", e);
        }
    }

    private Map<Integer, TurnoStats[]> getStatsPorTienda(Connection conn, String campanaId) throws SQLException {
        Map<Integer, TurnoStats[]> statsMap = new HashMap<>();
        String sql = "SELECT Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4 FROM voluntarios_en_campana WHERE Campana = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, campanaId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    for (int i = 1; i <= 4; i++) {
                        int tiendaId = rs.getInt("Turno" + i);
                        if (tiendaId > 0) {
                            statsMap.putIfAbsent(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});
                            
                            TurnoStats stats = statsMap.get(tiendaId)[i - 1];
                            stats.voluntarios++;
                            
                            String comentario = rs.getString("Comentario" + i);
                            if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                                try {
                                    String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                                    stats.acompanantes += Integer.parseInt(numStr);
                                } catch (Exception e) { /* Ignorar */ }
                            }
                        }
                    }
                }
            }
        }
        return statsMap;
    }
    
    private String getActiveCampaign() throws SQLException {
        String campanaId = null;
        String sql = "SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1";
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                campanaId = rs.getString("Campana");
            }
        }
        return campanaId;
    }

    private static class TurnoStats {
        int voluntarios = 0;
        int acompanantes = 0;
    }
    
    private String escapeCsv(String data) {
        if (data == null) return "";
        if (data.contains("\"") || data.contains(";") || data.contains("\n")) {
            data = data.replace("\"", "\"\"");
        }
        return "\"" + data + "\"";
    }
}