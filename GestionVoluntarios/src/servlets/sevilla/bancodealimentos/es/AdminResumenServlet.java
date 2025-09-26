// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet para que los administradores vean un resumen de la ocupaci�n
 * de las tiendas para una campa�a espec�fica.
 */
@WebServlet("/admin-resumen")
public class AdminResumenServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    public void init() throws ServletException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ServletException("Error: No se pudo cargar el driver de MySQL.", e);
        }
    }

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

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        String campanaId = request.getParameter("campana");
        if (campanaId == null || campanaId.trim().isEmpty()) {
            // Si no se especifica una campa�a, se busca la activa por defecto.
            try {
                campanaId = getActiveCampaign();
                if (campanaId == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campa�a activa.");
                    return;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al buscar la campa�a activa.");
                return;
            }
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            // Paso 1: Obtener las asignaciones y procesarlas en un mapa para un acceso r�pido.
            Map<Integer, TurnoStats[]> statsPorTienda = getStatsPorTienda(conn, campanaId);

            // Paso 2: Obtener todas las tiendas y construir el JSON final.
            String jsonResult = buildFinalJson(conn, statsPorTienda);
            out.print(jsonResult);

        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al generar el resumen.");
        }
        
        out.flush();
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
                                } catch (Exception e) { /* Ignorar si el formato es incorrecto */ }
                            }
                        }
                    }
                }
            }
        }
        return statsMap;
    }

    private String buildFinalJson(Connection conn, Map<Integer, TurnoStats[]> statsPorTienda) throws SQLException {
        StringBuilder jsonBuilder = new StringBuilder("[");
        String sql = "SELECT codigo, denominacion, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4 FROM tiendas WHERE disponible = 'S' ORDER BY denominacion";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            boolean first = true;
            while (rs.next()) {
                if (!first) jsonBuilder.append(",");
                
                int tiendaId = rs.getInt("codigo");
                TurnoStats[] stats = statsPorTienda.getOrDefault(tiendaId, new TurnoStats[]{new TurnoStats(), new TurnoStats(), new TurnoStats(), new TurnoStats()});

                jsonBuilder.append("{");
                jsonBuilder.append("\"codigo\":").append(tiendaId).append(",");
                jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                
                for (int i = 1; i <= 4; i++) {
                    jsonBuilder.append("\"huecosTurno").append(i).append("\":").append(rs.getInt("HuecosTurno" + i)).append(",");
                    jsonBuilder.append("\"voluntariosTurno").append(i).append("\":").append(stats[i-1].voluntarios).append(",");
                    jsonBuilder.append("\"acompanantesTurno").append(i).append("\":").append(stats[i-1].acompanantes).append(i == 4 ? "" : ",");
                }
                
                jsonBuilder.append("}");
                first = false;
            }
        }
        jsonBuilder.append("]");
        return jsonBuilder.toString();
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
    
    // Clase interna para ayudar a almacenar las estad�sticas de cada turno
    private static class TurnoStats {
        int voluntarios = 0;
        int acompanantes = 0;
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

