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

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Servlet que devuelve una lista completa de todas las tiendas activas
 * para poder consultar sus nombres y coordenadas.
 */
@WebServlet("/todas-las-tiendas")
public class TodasLasTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(TodasLasTiendasServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("[");

        String sql = "SELECT codigo AS id, denominacion AS nombre, Direccion AS direccion, Lat AS lat, Lon AS lon FROM tiendas WHERE disponible = 'S'";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            boolean first = true;
            while (rs.next()) {
                if (!first) {
                    jsonBuilder.append(",");
                }
                jsonBuilder.append("{");
                jsonBuilder.append("\"id\":").append(rs.getInt("id")).append(",");
                jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("nombre"))).append("\",");
                jsonBuilder.append("\"direccion\":\"").append(escapeJson(rs.getString("direccion"))).append("\",");
                jsonBuilder.append("\"lat\":").append(rs.getBigDecimal("lat")).append(",");
                jsonBuilder.append("\"lon\":").append(rs.getBigDecimal("lon"));
                jsonBuilder.append("}");
                first = false;
            }
        } catch (SQLException e) {
            logger.error("Error consultando todas las tiendas", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar la base de datos.");
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