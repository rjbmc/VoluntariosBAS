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
 * Servlet que devuelve la lista detallada de voluntarios asignados
 * a una tienda y turno espec�ficos para una campa�a.
 */
@WebServlet("/admin-detalle-turno")
public class AdminDetalleTurnoServlet extends HttpServlet {
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
        
        String campanaId = request.getParameter("campana");
        String tiendaId = request.getParameter("tienda");
        String turnoNum = request.getParameter("turno");

        if (campanaId == null || tiendaId == null || turnoNum == null || !turnoNum.matches("[1-4]")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Par�metros 'campana', 'tienda' y 'turno' son requeridos.");
            return;
        }

        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("[");

        // Columnas din�micas basadas en el par�metro 'turno'
        String turnoCol = "Turno" + turnoNum;
        String comentarioCol = "Comentario" + turnoNum;

        String sql = "SELECT concat(v.Nombre, \" \", v.Apellidos,\" (\",v.Usuario,\")\") as Voluntario, v.Email, v.telefono, vec." + comentarioCol + " AS Comentario " +
                     "FROM voluntarios_en_campana vec " +
                     "JOIN voluntarios v ON vec.Usuario = v.Usuario " +
                     "WHERE vec.Campana = ? AND vec." + turnoCol + " = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);
            stmt.setInt(2, Integer.parseInt(tiendaId));

            try (ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) jsonBuilder.append(",");
                    
                    // Extraer n�mero de acompa�antes del comentario
                    int acompanantes = 0;
                    String comentario = rs.getString("Comentario");
                    if (comentario != null && comentario.startsWith("Voluntarios: ")) {
                        try {
                            String numStr = comentario.substring("Voluntarios: ".length()).split("\\.")[0].trim();
                            acompanantes = Integer.parseInt(numStr);
                        } catch (Exception e) { /* Ignorar si el formato es incorrecto */ }
                    }

                    jsonBuilder.append("{");
                    jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("Voluntario"))).append("\",");
                    jsonBuilder.append("\"email\":\"").append(escapeJson(rs.getString("Email"))).append("\",");
                    jsonBuilder.append("\"telefono\":\"").append(escapeJson(rs.getString("telefono"))).append("\",");
                    jsonBuilder.append("\"acompanantes\":").append(acompanantes);
                    jsonBuilder.append("}");
                    first = false;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los detalles del turno.");
            return;
        }

        jsonBuilder.append("]");
        out.print(jsonBuilder.toString());
        out.flush();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

