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
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet que procesa la verificaci�n de email a trav�s de un token.
 */
@WebServlet("/verificar-email")
public class VerificarEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    public void init() throws ServletException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ServletException("Error: No se pudo cargar el driver de MySQL.", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        String jsonResponse;

        String token = request.getParameter("token");

        if (token == null || token.trim().isEmpty()) {
            sendError(response, "Token no proporcionado.");
            return;
        }

        String sqlFindUser = "SELECT Usuario FROM voluntarios WHERE token_verificacion = ?";
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            String usuario = null;

            // 1. Buscar el usuario por el token de verificaci�n
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                    }
                }
            }

            // 2. Si se encuentra un usuario, se verifica su cuenta
            if (usuario != null) {
                String sqlUpdate = "UPDATE voluntarios SET verificado = 'S', token_verificacion = NULL, notificar = 'S' WHERE Usuario = ?";

                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, usuario);
                    stmtUpdate.executeUpdate();
                    
                    // --- CAMBIO: Se elimina el �ltimo par�metro de la llamada al log ---
                    LogUtil.logOperation(conn, "VERIFICACION_OK", usuario, "Cuenta verificada para el usuario: " + usuario);
                    jsonResponse = "{\"success\": true, \"message\": \"Tu cuenta ha sido verificada con �xito. Ya puedes iniciar sesi�n.\"}";
                }
            } else {
                jsonResponse = "{\"success\": false, \"message\": \"El enlace de verificaci�n no es v�lido o ya ha sido utilizado. Por favor, reg�strate de nuevo si el problema persiste.\"}";
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            jsonResponse = "{\"success\": false, \"message\": \"Error de base de datos al verificar la cuenta.\"}";
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        out.print(jsonResponse);
        out.flush();
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getWriter().print("{\"success\": false, \"message\": \"" + message + "\"}");
    }
}
