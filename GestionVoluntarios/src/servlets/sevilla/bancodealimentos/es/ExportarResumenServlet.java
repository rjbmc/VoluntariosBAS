package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet para exportar el resumen de una campaña a un fichero CSV.
 * Solo accesible para administradores.
 */
@WebServlet("/exportar-resumen")
public class ExportarResumenServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(ExportarResumenServlet.class);

    // Clase auxiliar interna para cálculos
    private static class TurnoStats {
        int voluntarios = 0;
        int acompanantes = 0;
    }

    // 2. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a ExportarResumen. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        String campanaId = request.getParameter("campana");
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            
            if (campanaId == null || campanaId.trim().isEmpty()) {
                campanaId = getActiveCampaign(conn);
                if (campanaId == null) {
                    logger.warn("Intento de exportación fallido: No hay campaña activa.");
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campaña activa para exportar.");
                    return;
                }
            }

            // Configuración de cabeceras para descarga de archivo CSV
            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"resumen_" + campanaId + ".csv\"");

            try (PrintWriter out = response.getWriter()) {
                // BOM para que Excel reconozca UTF-8 correctamente
                out.write('\ufeff');

                // Cabecera del CSV
                out.println("Codigo Tienda;Denominacion;Huecos T1;Voluntarios T1;Acompanantes T1;Total Ocup. T1;% Ocup. T1;Huecos T2;Voluntarios T2;Acompanantes T2;Total Ocup. T2;% Ocup. T2;Huecos T3;Voluntarios T3;Acompanantes T3;Total Ocup. T3;% Ocup. T3;Huecos T4;Voluntarios T4;Acompanantes T4;Total Ocup. T4;% Ocup. T4");

                // Cálculo de estadísticas en memoria
                Map<Integer, TurnoStats[]> statsPorTienda = getStatsPorTienda(conn, campanaId);

                // Consulta de tiendas y generación de filas
                String sql = "SELECT codigo, denominacion, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE disponible = 'S' ORDER BY denominacion";
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {
                    
                    while (rs.next()) {
                        int tiendaId = rs.getInt("codigo");
                        TurnoStats[] stats = statsPorTienda.getOrDefault(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});
                        
                        StringBuilder line = new StringBuilder();
                        line.append(tiendaId).append(";");
                        line.append(escapeCsv(rs.getString("denominacion"))).append(";");

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
                
                logger.info("Resumen CSV exportado exitosamente para la campaña {}", campanaId);
            }

        } catch (SQLException e) {
            logger.error("Error SQL al generar el CSV de resumen.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al generar el fichero de exportación.");
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
                                } catch (Exception e) { 
                                    logger.debug("Error parseando acompañantes: {}", comentario);
                                }
                            }
                        }
                    }
                }
            }
        }
        return statsMap;
    }

    private String getActiveCampaign(Connection conn) throws SQLException {
        String sql = "SELECT Campana FROM campanas WHERE estado = 'S' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("Campana");
            }
        }
        return null;
    }
    
    private String escapeCsv(String data) {
        if (data == null) return "";
        if (data.contains("\"") || data.contains(";") || data.contains("\n")) {
            data = data.replace("\"", "\"\"");
            return "\"" + data + "\"";
        }
        return data;
    }
}