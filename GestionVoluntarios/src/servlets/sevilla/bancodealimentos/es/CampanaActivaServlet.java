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

// --- CAMBIO: Importar la clase de configuraci�n ---
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Servlet que busca la campa�a activa (estado = 'S') y devuelve
 * sus datos en formato JSON.
 */
@WebServlet("/campana-activa")
public class CampanaActivaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(CampanaActivaServlet.class);

    // --- CAMBIO: Se eliminan las variables de conexi�n locales ---

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();

        String sql = "SELECT Campana, denominacion, fecha1, fecha2 FROM campanas WHERE estado = 'S' LIMIT 1";
        
        // --- CAMBIO: Usar las variables de la clase Config ---
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                StringBuilder jsonBuilder = new StringBuilder("{");
                jsonBuilder.append("\"campana\":\"").append(escapeJson(rs.getString("Campana"))).append("\",");
                jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                jsonBuilder.append("\"fecha1\":\"").append(rs.getDate("fecha1")).append("\",");
                jsonBuilder.append("\"fecha2\":\"").append(rs.getDate("fecha2")).append("\"");
                jsonBuilder.append("}");
                
                out.print(jsonBuilder.toString());

            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No se encontr� ninguna campa�a activa.");
            }

        } catch (SQLException e) {
            logger.error("Error al consultar la base de datos para campana activa", e);
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