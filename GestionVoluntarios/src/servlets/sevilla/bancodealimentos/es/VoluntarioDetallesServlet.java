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

import util.sevilla.bancodealimentos.es.DatabaseUtil;

/**
 * Servlet que devuelve los detalles de un voluntario.
 * Si es llamado por un admin con un par�metro 'usuario', devuelve los datos de ese usuario.
 * Si no, devuelve los datos del usuario en sesi�n.
 */
@WebServlet("/voluntario-detalles")
public class VoluntarioDetallesServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versi�n actualizada

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return isAdminAttr != null && (boolean) isAdminAttr;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesi�n de usuario activa.");
            return;
        }
 
        String usuarioAConsultar = request.getParameter("usuario");
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioFinal;

        if (isAdmin(session) && usuarioAConsultar != null && !usuarioAConsultar.trim().isEmpty()) {
            // Un administrador est� pidiendo los datos de un voluntario espec�fico.
            usuarioFinal = usuarioAConsultar;
        } else {
            // Un voluntario normal pide sus propios datos (o un admin los suyos).
            usuarioFinal = usuarioEnSesion;
        } 

        String sql = "SELECT Nombre, Apellidos, tiendaReferencia FROM voluntarios WHERE Usuario = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioFinal);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String nombre = rs.getString("Nombre");
                    String apellidos = rs.getString("Apellidos");
                    int tiendaId = rs.getInt("tiendaReferencia");
                    boolean tiendaEsNula = rs.wasNull();

                    StringBuilder jsonBuilder = new StringBuilder("{");
                    jsonBuilder.append("\"nombre\":\"").append(escapeJson(nombre)).append("\",");
                    jsonBuilder.append("\"apellidos\":\"").append(escapeJson(apellidos)).append("\"");
                    if (!tiendaEsNula) {
                        jsonBuilder.append(",\"tiendaReferencia\":").append(tiendaId);
                    }
                    jsonBuilder.append("}");
                    out.print(jsonBuilder.toString());

                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Usuario no encontrado.");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
        }
        
        out.flush();
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
