// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet que recopila todos los datos necesarios para generar el informe
 * en PDF de una campaña y los devuelve en formato JSON.
 */
@WebServlet("/informe-campana")
public class InformeCampanaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

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
            try {
                campanaId = getActiveCampaign();
                if (campanaId == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "No hay ninguna campaña activa.");
                    return;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al buscar la campaña activa.");
                return;
            }
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            
            StringBuilder jsonBuilder = new StringBuilder("{");

            // 1. Obtener detalles de la campaña
            String sqlCampana = "SELECT denominacion, fecha1, fecha2 FROM campanas WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlCampana)) {
                stmt.setString(1, campanaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        jsonBuilder.append("\"campana\":{");
                        jsonBuilder.append("\"id\":\"").append(escapeJson(campanaId)).append("\",");
                        jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                        jsonBuilder.append("\"fecha1\":\"").append(rs.getDate("fecha1")).append("\",");
                        jsonBuilder.append("\"fecha2\":\"").append(rs.getDate("fecha2")).append("\"");
                        jsonBuilder.append("},");
                    } else {
                        throw new ServletException("campaña no encontrada.");
                    }
                }
            }

            // 2. Obtener todas las asignaciones y construir la estructura de datos
            jsonBuilder.append("\"tiendas\":[");
            String sqlAsignaciones = "SELECT t.codigo, t.denominacion, v.Nombre, v.Apellidos, " +
                                     "vec.Turno1, vec.Comentario1, vec.Turno2, vec.Comentario2, " +
                                     "vec.Turno3, vec.Comentario3, vec.Turno4, vec.Comentario4 " +
                                     "FROM tiendas t " +
                                     "LEFT JOIN voluntarios_en_campana vec ON (t.codigo IN (vec.Turno1, vec.Turno2, vec.Turno3, vec.Turno4) AND vec.Campana = ?) " +
                                     "LEFT JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                                     "WHERE t.disponible = 'S' " +
                                     "ORDER BY t.denominacion, v.Apellidos, v.Nombre";

            try (PreparedStatement stmt = conn.prepareStatement(sqlAsignaciones)) {
                stmt.setString(1, campanaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    
                    int currentTiendaId = -1;
                    boolean firstTienda = true;

                    while (rs.next()) {
                        int tiendaId = rs.getInt("codigo");
                        if (tiendaId != currentTiendaId) {
                            if (!firstTienda) {
                                jsonBuilder.append("]}},"); // Cierra la tienda anterior
                            }
                            jsonBuilder.append("{");
                            jsonBuilder.append("\"codigo\":").append(tiendaId).append(",");
                            jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                            jsonBuilder.append("\"turnos\":{\"turno1\":[],\"turno2\":[],\"turno3\":[],\"turno4\":[]}}");
                            
                            // Volver atrás para rellenar los turnos de esta tienda
                            jsonBuilder.setLength(jsonBuilder.length() - 2); // Quita "}}"
                            jsonBuilder.append(",\"turno1\":[");
                            currentTiendaId = tiendaId;
                            firstTienda = false;
                        }
                        
                        // lógica para rellenar los turnos (simplificada para el ejemplo)
                        // Una implementación más robusta procesaría esto en Java después de la consulta.
                    }
                     if (!firstTienda) {
                        jsonBuilder.append("]}}");
                    }
                }
            }
            // NOTA: La consulta SQL anterior es compleja. Una alternativa más legible sería hacer
            // una consulta por cada tienda, pero sería menos eficiente. Para este caso,
            // una consulta más simple y procesado en Java es mejor. Se implementará
            // una versión más robusta y legible a continuación.
            
            // versión más robusta y legible
            jsonBuilder.setLength(0); // Reiniciar el builder
            jsonBuilder.append("{");
            // Repetir la obtención de detalles de campaña
            // ...
            
            // Esta es la implementación final que usaremos
            out.print(getReportDataAsJson(conn, campanaId));


        } catch (Exception e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al generar los datos del informe.");
        }
    }
    
    // método refactorizado y final para obtener los datos del informe
    private String getReportDataAsJson(Connection conn, String campanaId) throws SQLException, ServletException {
        StringBuilder jsonBuilder = new StringBuilder("{");

        // 1. Detalles de la campaña
        String sqlCampana = "SELECT denominacion, fecha1, fecha2 FROM campanas WHERE Campana = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sqlCampana)) {
            stmt.setString(1, campanaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    jsonBuilder.append("\"campana\":{");
                    jsonBuilder.append("\"id\":\"").append(escapeJson(campanaId)).append("\",");
                    jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                    jsonBuilder.append("\"fecha1\":\"").append(rs.getDate("fecha1")).append("\",");
                    jsonBuilder.append("\"fecha2\":\"").append(rs.getDate("fecha2")).append("\"");
                    jsonBuilder.append("},");
                } else {
                    throw new ServletException("campaña no encontrada.");
                }
            }
        }

        // 2. Obtener todas las tiendas y sus voluntarios por turno
        jsonBuilder.append("\"tiendas\":[");
        String sqlTiendas = "SELECT codigo, denominacion FROM tiendas WHERE disponible = 'S' ORDER BY denominacion";
        try (PreparedStatement stmtTiendas = conn.prepareStatement(sqlTiendas)) {
            try (ResultSet rsTiendas = stmtTiendas.executeQuery()) {
                boolean firstTienda = true;
                while (rsTiendas.next()) {
                    if (!firstTienda) jsonBuilder.append(",");
                    
                    int tiendaId = rsTiendas.getInt("codigo");
                    jsonBuilder.append("{");
                    jsonBuilder.append("\"codigo\":").append(tiendaId).append(",");
                    jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rsTiendas.getString("denominacion"))).append("\",");
                    jsonBuilder.append("\"turnos\":{");

                    // Para cada tienda, obtener los voluntarios de cada turno
                    for (int i = 1; i <= 4; i++) {
                        jsonBuilder.append("\"turno").append(i).append("\":[");
                        appendVoluntariosForTurno(conn, campanaId, tiendaId, i, jsonBuilder);
                        jsonBuilder.append(i == 4 ? "]" : "],");
                    }

                    jsonBuilder.append("}}");
                    firstTienda = false;
                }
            }
        }
        jsonBuilder.append("]}");
        return jsonBuilder.toString();
    }

    private void appendVoluntariosForTurno(Connection conn, String campanaId, int tiendaId, int turnoNum, StringBuilder jsonBuilder) throws SQLException {
        String turnoCol = "Turno" + turnoNum;
        String comentarioCol = "Comentario" + turnoNum;
        String sqlVoluntarios = "SELECT v.Nombre, v.Apellidos, vec." + comentarioCol + " AS Comentario " +
                                "FROM voluntarios_en_campana vec JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                                "WHERE vec.Campana = ? AND vec." + turnoCol + " = ? ORDER BY v.Apellidos, v.Nombre";
        
        try (PreparedStatement stmt = conn.prepareStatement(sqlVoluntarios)) {
            stmt.setString(1, campanaId);
            stmt.setInt(2, tiendaId);
            try (ResultSet rs = stmt.executeQuery()) {
                boolean firstVoluntario = true;
                while (rs.next()) {
                    if (!firstVoluntario) jsonBuilder.append(",");
                    
                    int acompanantes = 0;
                    String comentario = rs.getString("Comentario");
                    if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                        try {
                            String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                            acompanantes = Integer.parseInt(numStr);
                        } catch (Exception e) { /* Ignorar */ }
                    }

                    jsonBuilder.append("{");
                    jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("Nombre"))).append("\",");
                    jsonBuilder.append("\"apellidos\":\"").append(escapeJson(rs.getString("Apellidos"))).append("\",");
                    jsonBuilder.append("\"acompanantes\":").append(acompanantes);
                    jsonBuilder.append("}");
                    firstVoluntario = false;
                }
            }
        }
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
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

