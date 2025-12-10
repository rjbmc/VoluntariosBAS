package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/voluntario-detalles")
public class VoluntarioDetallesServlet extends HttpServlet {
    private static final long serialVersionUID = 4L; // Versión actualizada
    private static final Logger logger = LogManager.getLogger(VoluntarioDetallesServlet.class);

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
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }
 
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String sql = "SELECT Nombre, Apellidos, `DNI NIF`, Email, telefono, fechaNacimiento, cp, tiendaReferencia FROM voluntarios WHERE Usuario = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioEnSesion);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    JsonObject jsonResponse = new JsonObject();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

                    // *** SOLUCIÓN DEFINITIVA Y CORRECTA: Usar JsonObject ***
                    jsonResponse.addProperty("nombre", rs.getString("Nombre"));
                    jsonResponse.addProperty("apellidos", rs.getString("Apellidos"));
                    jsonResponse.addProperty("dni", rs.getString("DNI NIF"));
                    jsonResponse.addProperty("email", rs.getString("Email"));
                    jsonResponse.addProperty("telefono", rs.getString("telefono"));
                    
                    java.sql.Date fechaNac = rs.getDate("fechaNacimiento");
                    jsonResponse.addProperty("fechaNacimiento", (fechaNac != null) ? sdf.format(fechaNac) : "");
                    
                    jsonResponse.addProperty("cp", rs.getString("cp"));

                    int tiendaId = rs.getInt("tiendaReferencia");
                    if (!rs.wasNull()) {
                        jsonResponse.addProperty("tiendaReferencia", tiendaId);
                    }
                    
                    out.print(jsonResponse.toString());

                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Usuario no encontrado.");
                }
            }
        } catch (SQLException e) {
            logger.error("Error al consultar detalles de voluntario", e);
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
